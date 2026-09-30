package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.system.service.product.ScanModels.*;

class ScanCancellationTest {
    @TempDir Path dir;

    private ProductScanService service() throws Exception {
        Job job = new Job(); job.id = "cancel-test"; job.ownerId = 7;
        job.createdAt = "2026-09-30T00:00:00Z"; job.state = "COMPLETED";
        job.rules = new Request(); job.rules.platform = "taobao";
        Product product = new Product(); product.itemId = "123";
        product.state = "DONE"; product.verdict = "CLEAR";
        job.products.add(product);
        Files.write(dir.resolve(job.id + ".json"), JSON.toJSONBytes(job));
        return new ProductScanService(dir.toString(), "", "", 5000, 1, false, 1);
    }

    @SuppressWarnings("unchecked")
    private Job live(ProductScanService service) throws Exception {
        var field = ProductScanService.class.getDeclaredField("jobs"); field.setAccessible(true);
        return ((Map<String, Job>) field.get(service)).get("cancel-test");
    }

    @Test void stopAndPollingDoNotWaitForTaskWriteLockOrRewriteSnapshot() throws Exception {
        ProductScanService service = service();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Job job = live(service); job.state = "RUNNING";
            var publish = ProductScanService.class.getDeclaredMethod("publish", Job.class);
            publish.setAccessible(true); publish.invoke(service, job);
            byte[] before = Files.readAllBytes(dir.resolve(job.id + ".json"));
            synchronized (job) {
                Job response = caller.submit(() -> service.cancel(7, job.id)).get(2, TimeUnit.SECONDS);
                assertTrue(response.cancelRequested);
                assertTrue(job.cancelRequested);
                TaskPage page = caller.submit(() -> service.page(7, job.id, 1, 50, "ALL")).get(2, TimeUnit.SECONDS);
                assertTrue(page.job.cancelRequested);
                var stopping = ProductScanService.class.getDeclaredMethod("stopping", Job.class);
                stopping.setAccessible(true);
                assertEquals(true, caller.submit(() -> stopping.invoke(null, job)).get(2, TimeUnit.SECONDS));
            }
            assertArrayEquals(before, Files.readAllBytes(dir.resolve(job.id + ".json")));
            assertTrue(service.cancel(7, job.id).cancelRequested);
        } finally { caller.shutdownNow(); service.close(); }
    }

    @Test void stopChecksOwnershipAndDoesNotCancelFinishedTask() throws Exception {
        ProductScanService service = service();
        try {
            assertThrows(ServiceException.class, () -> service.cancel(8, "cancel-test"));
            assertFalse(service.cancel(7, "cancel-test").cancelRequested);
            Job restored = JSON.parseObject(JSON.toJSONBytes(live(service)), Job.class);
            assertEquals("COMPLETED", restored.state);
            assertFalse(restored.cancelRequested);
        } finally { service.close(); }
    }

    @Test void cancelledRunPersistsPartialResultsAndCanBeRestored() throws Exception {
        ProductScanService service = service();
        try {
            Job job = live(service); job.state = "QUEUED";
            Product pending = new Product(); pending.itemId = "456";
            job.products.add(pending);
            var broker = ProductScanService.class.getDeclaredField("remoteWorker");
            broker.setAccessible(true); broker.set(service, new ScanWorkerBroker(false, ""));
            service.cancel(7, job.id);
            var run = ProductScanService.class.getDeclaredMethod("run", Job.class);
            run.setAccessible(true); run.invoke(service, job);
            assertEquals("CANCELLED", job.state);
            assertEquals("DONE", job.products.get(0).state);
            assertEquals("CANCELLED", pending.state);
            assertTrue(pending.incomplete);
            ProductScanService restored = new ProductScanService(dir.toString(), "", "", 5000, 1, false, 1);
            try {
                Job saved = restored.get(7, job.id);
                assertEquals("CANCELLED", saved.state);
                assertEquals("DONE", saved.products.get(0).state);
                assertEquals("CANCELLED", saved.products.get(1).state);
                assertFalse(ScanRules.exportable(saved.products.get(1)));
            } finally { restored.close(); }
        } finally { service.close(); }
    }
}
