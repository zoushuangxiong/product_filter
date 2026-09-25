package com.ruoyi.system.service.product;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 旧版清单存储升级：逐条导出 source_csv，校验落盘后更新路径，全部成功后删除正文列
 *
 * 老版本只保存转换后的 CSV，无法恢复原始 Excel 二进制；清单名称保持不变。
 * 中途失败保留数据库正文，下次启动继续，禁止先删列导致历史文件丢失。
 *
 * @author ruoyi
 * @date 2026-09-23
 */
@Component
public class ExecutionListStorageMigration implements ApplicationRunner
{
    private static final Logger log = LoggerFactory.getLogger(ExecutionListStorageMigration.class);
    private final JdbcTemplate jdbc;
    private final ExecutionListFiles files;

    public ExecutionListStorageMigration(JdbcTemplate jdbc, ExecutionListFiles files)
    {
        this.jdbc = jdbc;
        this.files = files;
    }

    /** 启动时仅在旧正文列存在时迁移，不在正常列表查询中读取内容。 */
    @Override
    public void run(ApplicationArguments args) throws Exception
    {
        Integer legacy = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = database() and table_name = 'product_execution_list' and column_name = 'source_csv'", Integer.class);
        if (legacy == null || legacy == 0) return;
        Integer pathColumn = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = database() and table_name = 'product_execution_list' and column_name = 'file_path'", Integer.class);
        if (pathColumn == null || pathColumn == 0) throw new IllegalStateException("请先执行 sql/product_execution_list.sql，再启动服务迁移清单文件");
        long cursor = 0;
        while (true)
        {
            List<Map<String, Object>> rows = jdbc.queryForList("select id, platform, file_path, source_csv from product_execution_list where id > ? order by id limit 1", cursor);
            if (rows.isEmpty()) break;
            Map<String, Object> row = rows.get(0);
            cursor = ((Number) row.get("id")).longValue();
            String path = (String) row.get("file_path");
            if (path != null && Files.isRegularFile(files.resolve(path)))
            {
                ExecutionListSource.read(files.resolve(path), (String) row.get("platform"));
                continue;
            }
            String csv = (String) row.get("source_csv");
            if (csv == null || csv.isBlank()) throw new IllegalStateException("清单 " + cursor + " 缺少文件内容，已停止迁移并保留数据库正文");
            path = files.saveCsv(csv);
            try
            {
                var source = ExecutionListSource.read(files.resolve(path), (String) row.get("platform"));
                jdbc.update("update product_execution_list set file_path = ?, record_count = ?, product_count = ? where id = ?", path, source.recordCount(), source.productCount(), cursor);
            }
            catch (Exception e) { files.remove(path); throw e; }
        }
        // 只有每条旧记录均已有可读文件后，才移除数据库正文。
        jdbc.execute("alter table product_execution_list drop column source_csv");
        log.info("执行清单文件迁移完成，已删除数据库 source_csv 字段");
    }
}
