package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.ruoyi.system.service.product.ScanModels.*;
import static com.ruoyi.system.service.product.ScanWorkerProtocol.*;

/**
 * 同一 JAR 通过 application.yml 选择角色；worker 只加载配置，不启动 Web、数据库或 Redis。
 * 每个线程领取一个商品，心跳/进度独立发送；最终结果先存 outbox，再重试直到云端确认。
 */
public final class ScanWorkerMain {
    public static boolean startIfConfigured(String[] args) {
        var config = loadConfiguration(args);
        if (!isWorker(config)) return false;
        runWorker(config);
        return true;
    }
    static org.springframework.core.env.ConfigurableEnvironment loadConfiguration(String... args) {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(
                new org.springframework.core.env.SimpleCommandLinePropertySource(args));
        org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
    static boolean isWorker(org.springframework.core.env.Environment config) {
        return switch (config.getProperty("app.role", "server").strip()) {
            case "worker" -> true;
            case "server" -> false;
            default -> throw new IllegalArgumentException("app.role 必须为 worker 或 server");
        };
    }

    private final URI server;
    private final String token, workerId;
    private final String session = UUID.randomUUID().toString();
    private final Path data;
    private final int concurrency, ocrThreads;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeats;
    private static class HttpFailure extends Exception {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }
    private ScanWorkerMain(org.springframework.core.env.Environment p) throws Exception {
        server = serverUri(p.getProperty("worker.server-url", ""));
        token = System.getenv().getOrDefault("SCAN_WORKER_TOKEN", p.getProperty("worker.token", ""));
        if (token.length() < 32) throw new IllegalArgumentException("工作机连接凭证必须至少32字符");
        concurrency = Integer.parseInt(p.getProperty("worker.concurrency", "4"));
        ocrThreads = Integer.parseInt(p.getProperty("worker.ocr-threads", "1"));
        if (concurrency < 1 || concurrency > 8 || ocrThreads < 1 || ocrThreads > 4)
            throw new IllegalArgumentException("并发范围1～8；OCR内部线程1～4");
        data = Path.of(p.getProperty("worker.data-dir", "scan-worker-data")).toAbsolutePath();
        Files.createDirectories(data.resolve("outbox"));
        workerId = loadWorkerId(data, p.getProperty("worker.id", "auto"));
        workers = Executors.newFixedThreadPool(concurrency);
        heartbeats = Executors.newScheduledThreadPool(concurrency * 2);
    }
    static URI serverUri(String configured) {
        URI uri;
        try { uri = URI.create(configured.strip()); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("worker.server-url 必须是有效的 HTTP 或 HTTPS 地址");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(scheme) || "http".equals(scheme)))
            throw new IllegalArgumentException("worker.server-url 必须是 HTTP 或 HTTPS 地址，且不能包含账号、查询参数或片段");
        String path = uri.getRawPath().replaceAll("/{2,}", "/");
        if (!path.endsWith("/")) path += "/";
        return URI.create(scheme + "://" + uri.getRawAuthority() + path);
    }
    static String loadWorkerId(Path data, String configured) throws Exception {
        String id = configured.strip();
        if (id.isEmpty() || "auto".equals(id)) {
            Path file = data.resolve("worker-id");
            try { Files.writeString(file, "desktop-" + UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { }
            id = Files.readString(file).strip();
        }
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid worker.id");
        return id;
    }
    private static void runWorker(org.springframework.core.env.Environment config) {
        try {
            ScanWorkerMain app = new ScanWorkerMain(config);
            try (var channel = FileChannel.open(app.data.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("此工作目录已经有工作机程序运行");
                Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
                try { app.start(); } finally { app.stop(); }
            }
        } catch (Exception e) {
            // Do not print credentials or task bodies.
            System.err.println("工作机启动或运行失败: " + e.getClass().getSimpleName() + "; 请检查配置和工作目录");
            throw new IllegalStateException("Scan worker stopped", e);
        }
    }
    private void start() throws Exception {
        // Retry durable results before claiming anything else. A restarted cloud rejects stale leases with HTTP 409.
        try (var dirs = Files.list(data.resolve("outbox"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (Files.exists(dir.resolve("result.json"))) deliver(dir);
                else remove(dir);
            }
        }
        System.out.println("检测结果只回传文字和坐标，网页使用商品原图链接预览");
        EmbeddedOcr.configureWorker(concurrency, ocrThreads);
        EmbeddedOcr.initialize();
        System.out.println("工作机 " + workerId + " 已启动：商品并发=" + concurrency + "，OCR内部线程=" + ocrThreads + "；等待云端任务");
        for (int i = 0; i < concurrency; i++) workers.submit(this::loop);
        workers.shutdown();
        while (!workers.awaitTermination(1, TimeUnit.SECONDS)) { }
    }
    private void loop() {
        int failures = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (Files.getFileStore(data).getUsableSpace() < 2L * 1024 * 1024 * 1024)
                    throw new IllegalStateException("工作目录可用空间不足2GB");
                byte[] bytes = call("claim", message(null));
                if (bytes.length == 0) { pause(3); continue; }
                Input input = JSON.parseObject(bytes, Input.class);
                validate(input);
                execute(input); failures = 0;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (HttpFailure e) {
                if (e.status == 401 || e.status == 403) { System.err.println("工作机凭证被拒绝，停止领取任务"); stop(); return; }
                System.err.println(e.status == 423 ? "相同工作机ID的另一个进程仍在线，等待其退出或更换ID" : "云端接口暂不可用，HTTP " + e.status);
                if (!retry(++failures)) return;
            } catch (Exception e) {
                System.err.println("领取或处理失败，将重试：" + e.getClass().getSimpleName());
                if (!retry(++failures)) return;
            }
        }
    }
    /** 单商品生命周期：续租、检测、本地落盘、回传；退出时只清理尚未形成最终结果的目录。 */
    private void execute(Input input) throws Exception {
        long began = System.nanoTime();
        Path dir = data.resolve("outbox").resolve(UUID.randomUUID().toString());
        Path assets = dir.resolve("assets"); Files.createDirectories(assets);
        Object lock = new Object(); AtomicLong expiry = new AtomicLong(input.expiresAt);
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicReference<Product> progress = new AtomicReference<>();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                if (System.currentTimeMillis() >= expiry.get()) { revoked.set(true); return; }
                Reply reply = JSON.parseObject(call("heartbeat", message(input)), Reply.class);
                if (reply == null || !reply.accepted || reply.cancel || reply.expiresAt <= System.currentTimeMillis()) {
                    revoked.set(true); return;
                }
                expiry.set(reply.expiresAt);
            } catch (HttpFailure e) {
                if (e.status == 409 || e.status == 410) revoked.set(true);
                if (e.status == 401 || e.status == 403 || e.status == 423) stop();
            } catch (Exception e) {
                if (System.currentTimeMillis() >= expiry.get()) revoked.set(true);
            }
        }, 0, 10, TimeUnit.SECONDS);
        ScheduledFuture<?> reporter = heartbeats.scheduleWithFixedDelay(() -> {
            if (revoked.get() || !running.get()) return;
            try {
                Product snapshot = progress.getAndSet(null);
                if (snapshot != null) {
                    cachePreviews(assets);
                    Message report = message(input); report.product = snapshot;
                    requireAck(call("progress", report));
                }
            } catch (HttpFailure e) {
                if (e.status == 409 || e.status == 410) revoked.set(true);
                if (e.status == 401 || e.status == 403 || e.status == 423) stop();
            } catch (Exception ignored) { /* Final result delivery remains durable and retryable. */ }
        }, 0, 10, TimeUnit.SECONDS);
        java.util.function.BooleanSupplier stopped = () -> !running.get() || revoked.get()
                || Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= expiry.get();
        try {
            try {
                Runnable changed = () -> {
                    synchronized (lock) { progress.set(JSON.parseObject(JSON.toJSONString(input.product), Product.class)); }
                };
                boolean ready = ProductDetectionEngine.prepare(input.product, input.rules, input.whitelist,
                        input.importedTitles, input.rechecking, input.retrying,
                        () -> fetchProduct(input, stopped), lock, changed, () -> {});
                if (!ready) {
                    input.product.state = "INTERRUPTED"; input.product.incomplete = true;
                    input.product.error = "DAILY_LIMIT_EXCEEDED"; input.product.verdict = "REVIEW";
                } else if (!("DONE".equals(input.product.state) && !input.product.titleHits.isEmpty())) {
                    ProductDetectionEngine.execute(input.product, input.rules, input.whitelist, new HashMap<>(), assets,
                            new ScanGateway("", ""), lock, stopped, changed);
                }
            } catch (Exception e) {
                input.product.state = "FAILED"; input.product.verdict = "REVIEW";
                input.product.incomplete = true;
                input.product.error = e instanceof com.ruoyi.system.utils.taobao.TaobaoFetchException error
                        ? error.getCode().name() : "工作机检测执行失败";
            }
            if (stopped.getAsBoolean()) return;
            long detectionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            cachePreviews(assets);
            Saved saved = new Saved(); saved.input = input; saved.product = input.product;
            atomicWrite(dir.resolve("result.json"), JSON.toJSONBytes(saved));
            deliver(dir);
            System.out.println("商品 " + input.product.itemId + " 已结束回传流程：检测=" + detectionMillis
                    + "ms，本地保存及结果回传=" + (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) - detectionMillis) + "ms；未上传图片");
        } finally {
            heartbeat.cancel(true); reporter.cancel(true);
            if (!Files.exists(dir.resolve("result.json"))) remove(dir);
        }
    }
    private com.ruoyi.system.utils.taobao.TaobaoProductInfo fetchProduct(Input input,
            java.util.function.BooleanSupplier stopped) {
        int attempts = 0;
        while (!stopped.getAsBoolean()) {
            try {
                FetchReply reply = JSON.parseObject(call("fetch", message(input)), FetchReply.class);
                if (reply == null || !reply.accepted) throw new java.io.IOException("Invalid fetch reply");
                if (reply.error != null) throw new com.ruoyi.system.utils.taobao.TaobaoFetchException(
                        com.ruoyi.system.utils.taobao.TaobaoFetchException.Code.valueOf(reply.error));
                return reply.quotaReached ? null : reply.info();
            } catch (com.ruoyi.system.utils.taobao.TaobaoFetchException e) { throw e; }
            catch (HttpFailure e) {
                if (e.status == 409 || e.status == 410) throw new CancellationException();
                if (e.status == 401 || e.status == 403) { stop(); throw new CancellationException(); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            catch (Exception e) { /* Retry the same memoized request after network errors. */ }
            try { pause(Math.min(10, ++attempts)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        }
        throw new CancellationException();
    }
    /** 网络故障保留结果重试；失效租约丢弃，凭证/协议错误停止工作机并保留文件供排查。 */
    private void deliver(Path dir) throws Exception {
        Saved saved = JSON.parseObject(Files.readAllBytes(dir.resolve("result.json")), Saved.class);
        if (saved.input.version != 4 || saved.input.session == null) {
            Path obsolete = data.resolve("obsolete-results"); Files.createDirectories(obsolete);
            Files.move(dir, obsolete.resolve(dir.getFileName()));
            System.err.println("旧协议结果已保留到 obsolete-results；请在云端继续旧任务"); return;
        }
        int failures = 0;
        while (running.get()) {
            try {
                cachePreviews(dir.resolve("assets"));
                Message m = message(saved.input); m.product = saved.product;
                requireAck(call("complete", m)); remove(dir); return;
            } catch (HttpFailure e) {
                if (e.status == 409 || e.status == 410) { remove(dir); return; }
                if (e.status >= 400 && e.status < 500 && e.status != 408 && e.status != 429) {
                    System.err.println("结果回传被拒绝，已保留本地结果，请检查版本和凭证；HTTP " + e.status);
                    stop(); throw e;
                }
            } catch (InterruptedException e) { throw e; }
            catch (Exception e) { System.err.println("结果已保存本地，等待重传"); }
            pause(Math.min(30, 1L << Math.min(++failures, 5)));
        }
        throw new InterruptedException();
    }
    /** 保留旧版本约定的本地预览归档；当前网页直接使用原图 URL，不会读取或上传这些文件。 */
    private void cachePreviews(Path assets) throws Exception {
        Path previews = data.resolve("previews"); Files.createDirectories(previews);
        try (var files = Files.list(assets)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.jpg")).toList()) {
                Path dest = previews.resolve(file.getFileName());
                if (Files.exists(dest)) continue;
                Path tmp = Files.createTempFile(previews, "preview-", ".tmp");
                try {
                    Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(tmp); }
            }
        }
    }
    private Message message(Input input) {
        Message m = new Message(); m.workerId = workerId; m.capacity = concurrency; m.session = session;
        if (input != null) { m.taskId = input.taskId; m.lease = input.lease; m.session = input.session; }
        return m;
    }
    private byte[] call(String operation, Message message) throws Exception {
        var request = HttpRequest.newBuilder(server.resolve(operation)).timeout(Duration.ofSeconds(25))
                .header("X-Scan-Worker-Token", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.toJSONBytes(message))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new HttpFailure(response.statusCode());
            byte[] bytes = in.readNBytes(28 * 1024 * 1024 + 1);
            if (bytes.length > 28 * 1024 * 1024) throw new java.io.IOException("Response too large");
            return bytes;
        }
    }
    private static void requireAck(byte[] bytes) throws Exception {
        Reply reply = JSON.parseObject(bytes, Reply.class);
        if (reply == null || !reply.accepted) throw new java.io.IOException("Missing durable acknowledgement");
    }
    private static void validate(Input i) {
        if (i == null || i.version != 4 || i.taskId == null || i.lease == null || i.session == null
                || i.expiresAt < System.currentTimeMillis() + 15000 || i.product == null || i.rules == null
                || i.product.pictures == null || i.product.pictures.size() > 200 || i.whitelist == null || i.importedTitles == null)
            throw new IllegalArgumentException("Invalid work input");
        ScanRules.words(i.rules.imageWords);
    }
    private static void atomicWrite(Path path, byte[] bytes) throws Exception {
        Path tmp = Files.createTempFile(path.getParent(), "result-", ".tmp");
        try {
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    private static void remove(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private boolean retry(int failures) {
        try { pause(Math.min(30, 1L << Math.min(failures, 5))); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    private void pause(long seconds) throws InterruptedException { Thread.sleep(seconds * 1000 + ThreadLocalRandom.current().nextInt(250)); }
    private void stop() { running.set(false); workers.shutdownNow(); heartbeats.shutdownNow(); }
}
