package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.system.service.product.ScanModels.*;

/**
 * 单商品增量持久化。完整快照仅用于创建、规则变更及收尾；检测中只写当前商品。
 * 版本号确保完整快照提交后，旧增量不会覆盖新结果；旧版 JSON 可直接读取。
 */
final class ScanTaskStore {
    private final Path root;
    ScanTaskStore(Path root) { this.root = root; }
    public static class Part {
        public long revision;
        public String updatedAt;
        public Product product;
    }
    private Path directory(Job job) {
        if (!job.id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("任务ID无效");
        return root.resolve("task-parts").resolve(job.id);
    }
    /** 写单商品并刷盘；返回后才允许远程工作机删除本地结果。调用方必须持有任务锁。 */
    void writeProduct(Job job, Product product) throws Exception {
        if (!product.itemId.matches("[0-9]+")) throw new IllegalArgumentException("商品ID无效");
        Path directory = directory(job); Files.createDirectories(directory);
        Part part = new Part(); part.revision = ++job.storeRevision; part.product = product; part.updatedAt = job.updatedAt;
        atomicWrite(directory.resolve(product.itemId + ".json"), part);
        // 当前增量仅更新商品和最近更新时间；运行状态及规则由完整快照负责。
    }
    /** 恢复时按版本合并各商品的最后一次结果，不读取图片二进制内容。 */
    void restore(Job job) throws Exception {
        Path directory = directory(job);
        if (!Files.isDirectory(directory)) return;
        long baseRevision = job.storeRevision, latest = baseRevision;
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < job.products.size(); i++) indexes.put(job.products.get(i).itemId, i);
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Part part = JSON.parseObject(Files.readString(path), Part.class);
                if (part == null || part.product == null) throw new IllegalStateException("商品增量文件无效：" + path);
                Integer index = indexes.get(part.product.itemId);
                if (index == null || !path.getFileName().toString().equals(part.product.itemId + ".json"))
                    throw new IllegalStateException("商品增量与任务不匹配：" + path);
                if (part.revision > baseRevision) {
                    job.products.set(index, part.product);
                    if (Set.of("DONE", "FAILED").contains(part.product.state)) job.recheckItemIds.remove(part.product.itemId);
                    if (part.revision > latest && part.updatedAt != null) job.updatedAt = part.updatedAt;
                }
                latest = Math.max(latest, part.revision);
            }
        }
        job.storeRevision = latest;
    }
    /** 小文件原子替换，FileOutputStream 避免 NIO 按请求线程缓存整块直接内存。 */
    static void atomicWrite(Path destination, Object value) throws Exception {
        Path temp = Files.createTempFile(destination.getParent(), "scan-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temp.toFile())) {
                JSON.writeTo(output, value, JSONWriter.Feature.LargeObject);
                output.getFD().sync();
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
