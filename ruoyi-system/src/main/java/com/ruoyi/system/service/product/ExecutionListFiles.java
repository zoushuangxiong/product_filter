package com.ruoyi.system.service.product;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.exception.ServiceException;

/**
 * 执行清单私有文件存储，数据库仅保存相对路径；文件不会通过若依公开上传目录暴露
 *
 * @author ruoyi
 * @date 2026-09-23
 */
@Component
public class ExecutionListFiles
{
    private static final Logger log = LoggerFactory.getLogger(ExecutionListFiles.class);
    private final Path root;

    public ExecutionListFiles(@Value("${product.execution-list.storage:${user.home}/.product-filter/execution-lists}") String storage)
    {
        root = Path.of(storage).toAbsolutePath().normalize();
    }

    /** 保存上传原件，文件名使用服务端 UUID，限制实际读取字节数。 */
    public String save(MultipartFile file, String extension)
    {
        if (file == null || file.isEmpty()) throw new ServiceException("请上传商品文件");
        if (file.getSize() > ExecutionListSource.MAX_BYTES) throw new ServiceException("文件不能超过20 MB");
        String key = UUID.randomUUID() + extension;
        try
        {
            Files.createDirectories(root);
            try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(resolve(key)))
            {
                byte[] buffer = new byte[8192]; long total = 0; int length;
                while ((length = input.read(buffer)) != -1)
                {
                    total += length;
                    if (total > ExecutionListSource.MAX_BYTES) throw new ServiceException("文件不能超过20 MB");
                    output.write(buffer, 0, length);
                }
            }
            removeOnRollback(key);
            return key;
        }
        catch (Exception e)
        {
            remove(key);
            if (e instanceof ServiceException failure) throw failure;
            throw new ServiceException("清单文件保存失败，请检查存储目录权限和磁盘空间");
        }
    }

    /** 保存过滤快照或旧版 CSV 迁移文件，生成独立路径，不覆盖已有快照。 */
    public String saveCsv(String csv)
    {
        String key = UUID.randomUUID() + ".csv";
        try
        {
            Files.createDirectories(root);
            Files.writeString(resolve(key), csv, StandardCharsets.UTF_8);
            removeOnRollback(key);
            return key;
        }
        catch (Exception e) { remove(key); throw new ServiceException("清单文件保存失败，请检查存储目录权限和磁盘空间"); }
    }

    /** 拒绝任意路径，路径只能来自本服务生成的相对文件名。 */
    public Path resolve(String key)
    {
        if (key == null || !key.matches("[0-9a-f-]{36}\\.(csv|xlsx)")) throw new ServiceException("清单文件路径无效，请检查数据迁移状态");
        return root.resolve(key);
    }

    /** 数据库提交后才删除旧文件，防止事务回滚留下缺失文件的记录。 */
    public void removeAfterCommit(String key)
    {
        if (key == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { remove(key); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override public void afterCommit() { remove(key); }
        });
    }

    /** 保存记录失败时清理新文件。 */
    private void removeOnRollback(String key)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) remove(key); }
        });
    }

    /** 删除失败仅记录告警，不能将已经提交的数据库操作误报为失败。 */
    public void remove(String key)
    {
        if (key == null) return;
        try { Files.deleteIfExists(resolve(key)); }
        catch (Exception e) { log.warn("清单文件清理失败：{}", key, e); }
    }
}
