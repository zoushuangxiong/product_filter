package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.ruoyi.system.service.product.ScanModels.*;
import static com.ruoyi.system.service.product.ScanWorkerProtocol.*;

/** 云端适配层。原 Job 快照仍是任务记录；重启沿用原有 INTERRUPTED/人工继续语义。 */
@Component
public class ScanWorkerBroker {
    public static class Conflict extends RuntimeException { }
    public static class DuplicateWorker extends RuntimeException { }
    public static class Unavailable extends RuntimeException {
        Unavailable() { super("检测工作机离线或任务超时，请连接后继续检测"); }
    }
    private static final long LEASE_MS = 120_000, ACK_MS = 600_000;
    private final boolean enabled;
    private final String token;
    private final LongSupplier clock;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    // Completed receipts must not participate in every claim's queue scan/sort.
    private final Map<String, Ticket> receipts = new ConcurrentHashMap<>();
    private long lastPurge;
    private final Map<String, Node> nodes = new HashMap<>();
    private final Semaphore fetchSlots;
    private static class Node { String session; int capacity; long seen; }
    private volatile long lastSeen;
    private static class Ticket {
        Input input;
        volatile String worker, session;
        volatile long deadline, ackUntil;
        long created, firstAssignedAt;
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int attempts;
        volatile boolean done;
        Path assets;
        BooleanSupplier stopped;
        Consumer<Product> changed;
        Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> fetch;
        CompletableFuture<FetchReply> fetched;
        CompletableFuture<Void> completion = new CompletableFuture<>();
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ScanWorkerBroker(@Value("${product.scan.remote.enabled:false}") boolean enabled,
            @Value("${product.scan.remote.token:}") String token,
            @Value("${product.scan.remote.fetch-concurrency:4}") int fetchConcurrency) {
        this(enabled, token, System::currentTimeMillis, fetchConcurrency);
    }
    public ScanWorkerBroker(boolean enabled, String token) { this(enabled, token, System::currentTimeMillis, 4); }
    ScanWorkerBroker(boolean enabled, String token, LongSupplier clock) { this(enabled, token, clock, 4); }
    ScanWorkerBroker(boolean enabled, String token, LongSupplier clock, int fetchConcurrency) {
        this.enabled = enabled; this.token = token; this.clock = clock;
        if (fetchConcurrency < 1 || fetchConcurrency > 32) throw new IllegalArgumentException("fetch-concurrency must be 1..32");
        this.fetchSlots = new Semaphore(fetchConcurrency, true);
        if (enabled && (token == null || token.length() < 32))
            throw new IllegalArgumentException("远程检测需配置至少32字符的 product.scan.remote.token");
    }
    public boolean enabled() { return enabled; }
    public void checkAvailable() {
        if (enabled && clock.getAsLong() - lastSeen > 30_000)
            throw new ServiceException("检测工作机未连接，请先启动台式机上的检测程序");
    }
    public boolean authenticate(String supplied) {
        return enabled && supplied != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    public void execute(Product product, Request rules, List<WhitelistRule> whitelist, Path assets,
            BooleanSupplier stopped, Consumer<Product> changed) throws Exception {
        execute(product, rules, whitelist, List.of(), false, false,
                () -> { throw new IllegalStateException("No product source configured"); }, assets, stopped, changed);
    }
    public void execute(Product product, Request rules, List<WhitelistRule> whitelist,
            List<String> importedTitles, boolean rechecking, boolean retrying,
            Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> fetch, Path assets,
            BooleanSupplier stopped, Consumer<Product> changed) throws Exception {
        Ticket ticket = new Ticket(); ticket.created = clock.getAsLong(); ticket.input = new Input();
        ticket.input.taskId = UUID.randomUUID().toString();
        ticket.input.product = copy(product); ticket.input.rules = rules; ticket.input.whitelist = whitelist;
        ticket.input.importedTitles = List.copyOf(importedTitles);
        ticket.input.rechecking = rechecking; ticket.input.retrying = retrying; ticket.fetch = fetch;
        ticket.assets = assets; ticket.stopped = stopped; ticket.changed = changed;
        synchronized (this) { purge(); tickets.put(ticket.input.taskId, ticket); }
        try {
            while (true) {
                if (stopped.getAsBoolean()) throw new CancellationException();
                try { ticket.completion.get(1, TimeUnit.SECONDS); return; }
                catch (TimeoutException ignored) {
                    synchronized (this) {
                        long now = clock.getAsLong();
                        if ((ticket.attempts >= 3 && now > ticket.deadline)
                                || (now - Math.max(lastSeen, ticket.created) > 180_000) || ticket.firstAssignedAt > 0 && now - ticket.firstAssignedAt > 3_600_000)
                            throw new Unavailable();
                    }
                }
            }
        } finally {
            synchronized (this) {
                // Successful tickets retain a short-lived receipt for retries after a lost HTTP ACK.
                if (!ticket.done) tickets.remove(ticket.input.taskId);
            }
        }
    }
    public synchronized Input claim(Message m) {
        register(m); purge();
        long now = clock.getAsLong();
        long active = tickets.values().stream().filter(t -> !t.done && t.deadline > now
                && Objects.equals(t.worker, m.workerId) && Objects.equals(t.session, m.session)).count();
        if (active >= m.capacity) return null;
        for (Ticket t : tickets.values().stream().sorted(Comparator.comparingLong(t -> t.created)).toList()) {
            // A slow disk write for one product must not block claims/heartbeats for all machines.
            if (!t.lock.tryLock()) continue;
            try {
                if (t.done || t.attempts >= 3 || t.stopped.getAsBoolean()) continue;
                if (t.worker != null && t.deadline > now) continue;
                if (t.firstAssignedAt == 0) t.firstAssignedAt = now;
                t.worker = m.workerId; t.session = m.session; t.deadline = now + LEASE_MS; t.attempts++;
                t.input.lease = UUID.randomUUID().toString(); t.input.expiresAt = t.deadline; t.input.session = m.session;
                return JSON.parseObject(JSON.toJSONString(t.input), Input.class);
            } finally { t.lock.unlock(); }
        }
        return null;
    }
    private synchronized void register(Message m) {
        validate(m); long now = clock.getAsLong();
        Node node = nodes.get(m.workerId);
        if (node != null && now - node.seen < 45_000 && !Objects.equals(node.session, m.session))
            throw new DuplicateWorker();
        if (node == null) { node = new Node(); nodes.put(m.workerId, node); }
        node.session = m.session; node.capacity = m.capacity; node.seen = now; lastSeen = now;
    }
    public synchronized List<WorkerStatus> workers() {
        long now = clock.getAsLong();
        return nodes.entrySet().stream().map(e -> new WorkerStatus(e.getKey(), e.getValue().capacity,
                (int) tickets.values().stream().filter(t -> !t.done && t.deadline > now
                        && Objects.equals(t.worker, e.getKey()) && Objects.equals(t.session, e.getValue().session)).count(),
                e.getValue().seen, now - e.getValue().seen < 45_000))
                .sorted(Comparator.comparing(WorkerStatus::workerId)).toList();
    }
    /** 工作机决定何时获取商品；云端只提供受租约约束的缓存/额度/第三方代理，不执行检测。 */
    public FetchReply fetch(Message m) throws Exception {
        Ticket t = ticket(m); boolean first; CompletableFuture<FetchReply> memo;
        Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> source;
        t.lock.lock();
        try {
            owned(m, false);
            if (t.stopped.getAsBoolean()) throw new Conflict();
            first = t.fetched == null;
            if (first) t.fetched = new CompletableFuture<>();
            memo = t.fetched; source = t.fetch;
        } finally { t.lock.unlock(); }
        if (first) {
            FetchReply result; boolean acquired = false;
            try {
                fetchSlots.acquire(); acquired = true;
                t.lock.lock();
                try { owned(m, false); if (t.stopped.getAsBoolean()) throw new Conflict(); }
                finally { t.lock.unlock(); }
                result = FetchReply.from(source.get());
            } catch (Conflict e) {
                t.lock.lock();
                try { if (t.fetched == memo) t.fetched = null; memo.completeExceptionally(e); }
                finally { t.lock.unlock(); }
                throw e;
            } catch (com.ruoyi.system.utils.taobao.TaobaoFetchException e) {
                result = new FetchReply(); result.error = e.getCode().name();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                result = new FetchReply(); result.error = "PROVIDER_ERROR";
            } finally { if (acquired) fetchSlots.release(); }
            memo.complete(result);
        }
        FetchReply result = memo.get();
        t.lock.lock();
        try { owned(m, false); if (t.stopped.getAsBoolean()) throw new Conflict(); }
        finally { t.lock.unlock(); }
        return result;
    }
    public Reply heartbeat(Message m) {
        register(m);
        Ticket t = ticket(m); t.lock.lock();
        try {
            owned(m, false); lastSeen = clock.getAsLong();
            t.deadline = lastSeen + LEASE_MS;
            Reply reply = new Reply(); reply.accepted = true; reply.expiresAt = t.deadline;
            reply.cancel = t.stopped.getAsBoolean(); return reply;
        } finally { t.lock.unlock(); }
    }
    public Reply progress(Message m) {
        Ticket t = ticket(m); t.lock.lock();
        try {
            owned(m, false);
            if (t.stopped.getAsBoolean()) throw new Conflict();
            validateProduct(t, m.product, false);
            Product snapshot = copy(m.product);
            if (Set.of("DONE", "FAILED", "INTERRUPTED").contains(snapshot.state)) snapshot.state = "SCANNING";
            t.changed.accept(snapshot);
            return accepted();
        } finally { t.lock.unlock(); }
    }
    public Reply complete(Message m) {
        Ticket t = ticket(m); t.lock.lock();
        try {
            owned(m, true);
            if (t.done) return accepted();
            if (t.stopped.getAsBoolean()) throw new Conflict();
            validateProduct(t, m.product, true);
            // changed() writes the original Job snapshot before this endpoint acknowledges success.
            t.changed.accept(copy(m.product));
            t.done = true; t.ackUntil = clock.getAsLong() + ACK_MS;
            receipts.put(t.input.taskId, t);
            tickets.remove(t.input.taskId, t);
            t.completion.complete(null);
            // Receipt needs identity only; release potentially large rules, OCR and Job-capturing callbacks.
            t.input.product = null; t.input.rules = null; t.input.whitelist = null; t.input.importedTitles = List.of();
            t.changed = null; t.stopped = null; t.assets = null; t.fetch = null; t.fetched = null;
            return accepted();
        } finally { t.lock.unlock(); }
    }
    private Ticket ticket(Message m) {
        validate(m); Ticket t = m.taskId == null ? null : tickets.get(m.taskId);
        if (t == null && m.taskId != null) t = receipts.get(m.taskId);
        if (t == null) throw new Conflict(); return t;
    }
    private Ticket owned(Message m, boolean allowDone) {
        Ticket t = ticket(m);
        if (t == null || !Objects.equals(t.worker, m.workerId) || !Objects.equals(t.session, m.session) || !Objects.equals(t.input.lease, m.lease)
                || t.done && !allowDone || !t.done && t.deadline <= clock.getAsLong()) throw new Conflict();
        return t;
    }
    private void validateProduct(Ticket t, Product p, boolean complete) {
        Product expected = t.input.product;
        if (p == null || !Objects.equals(p.itemId, expected.itemId) || !Objects.equals(p.platform, expected.platform)
                || p.pictures == null || p.pictures.size() > 200 || p.warnings == null
                || p.titleHits == null
                || !Set.of("PENDING", "FETCHING", "SCANNING", "DONE", "FAILED", "CANCELLED", "INTERRUPTED").contains(p.state)
                || !Set.of("MATCHED", "REVIEW", "CLEAR").contains(p.verdict)
                || complete && !Set.of("DONE", "FAILED").contains(p.state)
                    && !("INTERRUPTED".equals(p.state) && "DAILY_LIMIT_EXCEEDED".equals(p.error))) throw new IllegalArgumentException("Invalid product result");
        for (int i = 0; i < p.pictures.size(); i++) {
            Picture pic = p.pictures.get(i);
            if (pic == null || !allowedPicture(t, pic) || pic.hits == null
                    || pic.previewKey != null && !pic.previewKey.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid image result");
            if (complete && "DONE".equals(pic.state) && (pic.ocr == null || pic.previewKey == null))
                throw new IllegalArgumentException("Missing image evidence");
        }
        if (complete && "CLEAR".equals(p.verdict)) {
            List<Picture> required = requiredPictures(t);
            if (required.size() != p.pictures.size()) throw new IllegalArgumentException("Missing required images");
            for (int i = 0; i < required.size(); i++) {
                Picture actual = p.pictures.get(i), original = required.get(i);
                if (!Objects.equals(actual.url, original.url) || !Objects.equals(actual.kind, original.kind)
                        || actual.index != original.index) throw new IllegalArgumentException("Wrong image sequence");
            }
        }
        if (complete && "CLEAR".equals(p.verdict) && (!"DONE".equals(p.state) || p.incomplete || !p.titleHits.isEmpty()
                || p.pictures.stream().anyMatch(pic -> !"DONE".equals(pic.state) || !pic.hits.isEmpty() || pic.qrCodes > 0)))
            throw new IllegalArgumentException("Incomplete result cannot pass");
    }
    private List<Picture> requiredPictures(Ticket t) {
        FetchReply source = t.fetched == null ? null : t.fetched.getNow(null);
        boolean reset = t.input.rechecking && "MATCHED".equals(t.input.product.verdict)
                || t.input.retrying && source != null && source.error == null && !source.quotaReached;
        List<Picture> result = new ArrayList<>(reset ? List.of() : t.input.product.pictures);
        if (source != null && source.error == null && !source.quotaReached) {
            for (String kind : List.of("MAIN", "DETAIL")) {
                List<String> urls = "MAIN".equals(kind) ? source.mainImages : source.detailImages;
                for (int i = 0; i < urls.size() && result.size() < 200; i++) {
                    Picture pic = new Picture(); pic.kind = kind; pic.index = i + 1; pic.url = urls.get(i); result.add(pic);
                }
            }
        }
        return result;
    }
    private boolean allowedPicture(Ticket t, Picture pic) {
        if (t.input.product.pictures.stream().anyMatch(old -> Objects.equals(old.url, pic.url)
                && Objects.equals(old.kind, pic.kind) && old.index == pic.index)) return true;
        FetchReply source = t.fetched == null ? null : t.fetched.getNow(null);
        if (source == null || source.error != null || source.quotaReached) return false;
        List<String> urls = "MAIN".equals(pic.kind) ? source.mainImages
                : "DETAIL".equals(pic.kind) ? source.detailImages : List.of();
        return pic.index >= 1 && pic.index <= urls.size() && Objects.equals(pic.url, urls.get(pic.index - 1));
    }
    private static void validate(Message m) {
        if (m == null || m.version != 4 || m.workerId == null || !m.workerId.matches("[a-zA-Z0-9_-]{1,64}")
                || m.session == null || !m.session.matches("[a-zA-Z0-9_-]{1,64}") || m.capacity < 1 || m.capacity > 8)
            throw new IllegalArgumentException("Invalid worker protocol");
    }
    private void purge() {
        long now = clock.getAsLong();
        if (now - lastPurge < 30_000) return;
        lastPurge = now;
        receipts.values().removeIf(t -> t.ackUntil < now);
        nodes.values().removeIf(n -> now - n.seen > 86_400_000);
    }
    private static Product copy(Product p) { return JSON.parseObject(JSON.toJSONString(p), Product.class); }
    private static Reply accepted() { Reply r = new Reply(); r.accepted = true; return r; }
}
