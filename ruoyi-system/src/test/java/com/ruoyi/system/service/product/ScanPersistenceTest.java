package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.system.service.product.ScanModels.*;
import static com.ruoyi.system.service.product.ScanWorkerProtocol.*;

/** 保存失败、请求线程直接内存保留及远程结果确认的回归测试，不调用商品接口。 */
class ScanPersistenceTest {
    @TempDir Path dir;

    ProductScanService service() throws Exception {
        Job job = new Job(); job.id = "persistence-test"; job.ownerId = 7;
        job.createdAt = "2026-10-04T00:00:00Z"; job.state = "COMPLETED";
        job.rules = new Request(); job.rules.platform = "taobao";
        Product product = new Product(); product.itemId = "123"; product.platform = "taobao";
        job.products.add(product);
        Files.writeString(dir.resolve(job.id + ".json"), JSON.toJSONString(job));
        return new ProductScanService(dir.toString(), "", "", 5000, 1, true, 1);
    }
    Job live(ProductScanService service) throws Exception {
        Field field = ProductScanService.class.getDeclaredField("jobs"); field.setAccessible(true);
        return (Job) ((java.util.Map<?, ?>) field.get(service)).get("persistence-test");
    }
    void invoke(ProductScanService service, String name, Job job) throws Exception {
        Method method = ProductScanService.class.getDeclaredMethod(name, Job.class);
        method.setAccessible(true); method.invoke(service, job);
    }
    void blockSnapshot(Job job) throws Exception {
        Path target = dir.resolve(job.id + ".json");
        Files.move(target, dir.resolve("previous.snapshot"));
        Files.createDirectory(target); Files.writeString(target.resolve("blocker"), "disk failure fixture");
    }
    long directBytes() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> "direct".equals(pool.getName())).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
    }
    @Test void wholeSnapshotWriteDoesNotRetainWholeFileDirectBuffer() throws Exception {
        ProductScanService service = service(); ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Job job = live(service); job.error = "x".repeat(1024 * 1024);
            long before = directBytes();
            writer.submit(() -> { invoke(service, "save", job); return null; }).get(5, TimeUnit.SECONDS);
            assertTrue(Files.size(dir.resolve(job.id + ".json")) > 1024 * 1024);
            assertTrue(directBytes() - before < 256 * 1024, "Idle writer must not retain a snapshot-sized direct buffer");
            assertEquals(job.error, JSON.parseObject(Files.readString(dir.resolve(job.id + ".json")), Job.class).error);
        } finally { writer.shutdownNow(); service.close(); }
    }
    @Test void persistentWriteFailureStillPublishesTerminalState() throws Exception {
        ProductScanService service = service();
        try {
            Job job = live(service); job.state = "QUEUED"; blockSnapshot(job);
            invoke(service, "run", job);
            assertEquals("FAILED", service.get(7, job.id).state);
            assertEquals("FAILED", service.page(7, job.id, 1, 50, "ALL").job.state);
            assertNotNull(service.get(7, job.id).completedAt);
            assertTrue(service.get(7, job.id).error.contains("未保存"));
            assertEquals("INTERRUPTED", job.products.get(0).state);
            assertTrue(Files.exists(dir.resolve("previous.snapshot")));
            try (var files = Files.list(dir)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
        } finally { service.close(); }
    }
    @Test void memoryErrorAlsoPublishesFailedState() throws Exception {
        ProductScanService service = service();
        try {
            Field field = ProductScanService.class.getDeclaredField("remoteWorker"); field.setAccessible(true);
            field.set(service, new ScanWorkerBroker(false, "") {
                @Override public boolean enabled() { throw new OutOfMemoryError("simulated direct buffer failure"); }
            });
            Job job = live(service); job.state = "QUEUED";
            invoke(service, "run", job);
            assertEquals("FAILED", service.get(7, job.id).state);
            assertEquals("FAILED", JSON.parseObject(Files.readString(dir.resolve(job.id + ".json")), Job.class).state);
        } finally { service.close(); }
    }
    @Test void remoteSaveFailureRejectsAckAndEndsAsFailedRatherThanCancelled() throws Exception {
        ProductScanService service = service(); ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            ScanWorkerBroker broker = new ScanWorkerBroker(true, "test-worker-secret-0123456789abcdef");
            Field field = ProductScanService.class.getDeclaredField("remoteWorker"); field.setAccessible(true); field.set(service, broker);
            Job job = live(service); job.state = "QUEUED";
            Future<?> running = coordinator.submit(() -> { invoke(service, "run", job); return null; });
            Message message = new Message(); message.workerId = "test-worker"; message.session = "session"; message.capacity = 1;
            Input input = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (input == null && System.nanoTime() < deadline) { input = broker.claim(message); if (input == null) Thread.sleep(10); }
            assertNotNull(input);
            Path parts = dir.resolve("task-parts").resolve(job.id); Files.createDirectories(parts);
            Files.createDirectory(parts.resolve("123.json"));
            Files.writeString(parts.resolve("123.json/blocker"), "disk failure fixture");
            message.taskId = input.taskId; message.lease = input.lease;
            message.product = input.product; message.product.state = "DONE"; message.product.verdict = "CLEAR";
            assertThrows(RuntimeException.class, () -> broker.complete(message));
            running.get(5, TimeUnit.SECONDS);
            assertEquals("FAILED", service.get(7, job.id).state);
            assertEquals("DONE", job.products.get(0).state);
            assertThrows(ScanWorkerBroker.Conflict.class, () -> broker.complete(message));
        } finally { coordinator.shutdownNow(); service.close(); }
    }
}
