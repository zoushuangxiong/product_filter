package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.system.service.product.ScanModels.*;

/** 用固定时间和模拟工作机验证跨日续跑，不等待零点、不请求付费接口。 */
class ScanQuotaResumeTest {
    @TempDir Path dir;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    void set(Object object, String name, Object value) throws Exception {
        var field = ProductScanService.class.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    Object call(ProductScanService service, String name, Class<?>[] types, Object... values) throws Exception {
        var method = ProductScanService.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(service, values);
    }
    Job job(String id, String state) {
        Job job = new Job(); job.id = id; job.ownerId = 7; job.createdAt = "2026-10-07T12:00:00Z";
        job.state = state; job.quotaResumeDate = "2026-10-08"; job.rules = new Request(); job.rules.platform = "taobao";
        Product done = new Product(); done.itemId = "123"; done.state = "DONE";
        Product pending = new Product(); pending.itemId = "456";
        job.products.add(done); job.products.add(pending); return job;
    }
    ProductScanService service(Job job, AtomicInteger calls) throws Exception {
        Files.writeString(dir.resolve(job.id + ".json"), JSON.toJSONString(job));
        var service = new ProductScanService(dir.toString(), "test-key", "test-secret", 2, 1, true, 1);
        set(service, "remoteWorker", new ScanWorkerBroker(true, "test-worker-secret-0123456789abcdef") {
            @Override public void checkAvailable() { }
            @Override public void execute(Product product, Request rules, List<WhitelistRule> whitelist,
                    List<String> titles, boolean rechecking, boolean retrying,
                    Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> fetch,
                    BooleanSupplier stopped, Consumer<Product> changed) {
                calls.incrementAndGet(); product.state = "DONE"; product.error = null;
                changed.accept(product);
            }
        });
        return service;
    }
    void time(ProductScanService service, String local) throws Exception {
        set(service, "quotaClock", Clock.fixed(LocalDateTime.parse(local).atZone(ZONE).toInstant(), ZONE));
    }
    @Test void resumesOnlyAfterMidnightAndKeepsCompletedProducts() throws Exception {
        AtomicInteger calls = new AtomicInteger(); var service = service(job("waiting", "WAITING_QUOTA"), calls);
        try {
            time(service, "2026-10-07T23:59:00");
            assertEquals(60000, service.nextQuotaDelayMillis());
            service.resumeQuotaTasks(); assertEquals(0, calls.get());
            time(service, "2026-10-08T00:01:00"); service.resumeQuotaTasks();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!"COMPLETED".equals(service.get(7, "waiting").state) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("COMPLETED", service.get(7, "waiting").state);
            assertEquals(1, calls.get());
            service.resumeQuotaTasks(); assertEquals(1, calls.get());
        } finally { service.close(); }
    }
    @Test void quotaExhaustionLeavesRemainingProductsIncompleteAndSchedulesTomorrow() throws Exception {
        Job fixture = job("exhausted", "COMPLETED"); fixture.quotaResumeDate = null;
        var service = service(fixture, new AtomicInteger());
        try {
            time(service, "2026-10-08T12:00:00");
            set(service, "remoteWorker", new ScanWorkerBroker(true, "test-worker-secret-0123456789abcdef") {
                @Override public void execute(Product product, Request rules, List<WhitelistRule> whitelist,
                        List<String> titles, boolean rechecking, boolean retrying,
                        Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> fetch,
                        BooleanSupplier stopped, Consumer<Product> changed) {
                    product.state = "INTERRUPTED"; product.error = "DAILY_LIMIT_EXCEEDED";
                    product.incomplete = true; changed.accept(product);
                }
            });
            var field = ProductScanService.class.getDeclaredField("jobs"); field.setAccessible(true);
            Job live = (Job) ((Map<?, ?>) field.get(service)).get("exhausted");
            call(service, "run", new Class<?>[]{Job.class}, live);
            assertEquals("WAITING_QUOTA", service.get(7, live.id).state);
            assertEquals("2026-10-09", service.page(7, live.id, 1, 50, "ALL").job.quotaResumeDate);
            assertEquals("DONE", live.products.get(0).state);
            assertFalse(ScanRules.exportable(live.products.get(1)));
            assertEquals("WAITING_QUOTA", JSON.parseObject(Files.readString(dir.resolve(live.id + ".json")), Job.class).state);
        } finally { service.close(); }
    }
    @Test void manualStopPersistsAndNeverAutoResumesAfterRestart() throws Exception {
        AtomicInteger calls = new AtomicInteger(); var service = service(job("stopped", "WAITING_QUOTA"), calls);
        try { assertEquals("CANCELLED", service.cancel(7, "stopped").state); }
        finally { service.close(); }
        var restored = new ProductScanService(dir.toString(), "test-key", "test-secret", 2, 1, true, 1);
        try {
            time(restored, "2026-10-09T00:01:00"); restored.resumeQuotaTasks();
            assertEquals("CANCELLED", restored.get(7, "stopped").state);
            assertNull(restored.get(7, "stopped").quotaResumeDate);
        } finally { restored.close(); }
    }
    @Test void oldDayCompletionsDoNotConsumeOrReleaseNewDayReservations() throws Exception {
        var service = new ProductScanService(dir.toString(), "test-key", "test-secret", 2, 1, true, 1);
        try {
            time(service, "2026-10-07T23:59:00");
            LocalDate old = (LocalDate) call(service, "reserveProviderSlot", new Class<?>[0]);
            time(service, "2026-10-08T00:00:01"); service.usage();
            LocalDate today = (LocalDate) call(service, "reserveProviderSlot", new Class<?>[0]);
            call(service, "recordProviderSuccess", new Class<?>[]{LocalDate.class}, old);
            call(service, "recordProviderSuccess", new Class<?>[]{LocalDate.class}, today);
            assertEquals(1, service.usage().get("used"));
            assertNotNull(call(service, "reserveProviderSlot", new Class<?>[0]));
            assertNull(call(service, "reserveProviderSlot", new Class<?>[0]));
        } finally { service.close(); }
    }
    @Test void onlyChangedProductIsWrittenAndOldPartsCannotOverrideCheckpoint() throws Exception {
        Job job = job("parts", "RUNNING"); var service = service(job, new AtomicInteger());
        try {
            // 构造器已将运行中的旧快照恢复为中断，以当前已保存版本为基准。
            job = JSON.parseObject(Files.readString(dir.resolve("parts.json")), Job.class);
            byte[] before = Files.readAllBytes(dir.resolve("parts.json"));
            var store = new ScanTaskStore(dir); job.products.get(1).title = "增量结果"; job.updatedAt = Instant.now().toString();
            store.writeProduct(job, job.products.get(1));
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("parts.json")));
            Job restored = JSON.parseObject(before, Job.class); store.restore(restored);
            assertEquals("增量结果", restored.products.get(1).title);
            restored.products.get(1).title = "完整新快照";
            call(service, "save", new Class<?>[]{Job.class}, restored);
            Job latest = JSON.parseObject(Files.readString(dir.resolve("parts.json")), Job.class); store.restore(latest);
            assertEquals("完整新快照", latest.products.get(1).title);
        } finally { service.close(); }
    }
}
