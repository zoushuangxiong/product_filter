package com.ruoyi.system.service.product;

import com.ruoyi.common.exception.ServiceException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 旧版 Excel 导入保持商品ID精度、列位置和清单过滤规则。 */
class ExecutionListXlsTest {
    @TempDir Path dir;

    Path file(boolean header, double id) throws Exception {
        Path path = dir.resolve("source.XLS");
        try (var workbook = new HSSFWorkbook(); var out = Files.newOutputStream(path)) {
            var sheet = workbook.createSheet("商品");
            if (header) {
                sheet.createRow(0).createCell(0).setCellValue("商品ID");
                sheet.getRow(0).createCell(2).setCellValue("商品标题");
            }
            for (int i = 0; i < 2; i++) {
                var row = sheet.createRow(i + (header ? 1 : 0));
                row.createCell(0).setCellValue(id);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.createDataFormat().getFormat("0.00E+00"));
                row.getCell(0).setCellStyle(style);
                row.createCell(2).setCellValue("测试商品");
            }
            workbook.createSheet("忽略此表").createRow(0).createCell(0).setCellValue("非商品数据");
            workbook.write(out);
        }
        return path;
    }
    @Test void readsFirstSheetAndPreservesNumericIdsDuplicateRowsAndEmptyColumns() throws Exception {
        var table = ExecutionListSource.read(file(true, 1072093000183d), "taobao");
        assertEquals(2, table.recordCount()); assertEquals(1, table.productCount());
        assertEquals("1072093000183", table.items().get(0));
        assertEquals("", table.rows().get(1).get(1));
        assertEquals("测试商品", table.rows().get(1).get(2));
        assertEquals(0, ExecutionListSource.filter(table, Set.of("1072093000183")).recordCount());
        assertTrue(table.csv().contains("商品标题"));
    }
    @Test void requiresHeaderAndRejectsInexactNumericIds() throws Exception {
        assertThrows(ServiceException.class, () -> ExecutionListSource.read(file(false, 1072093000183d), "taobao"));
        assertThrows(ServiceException.class, () -> ExecutionListSource.read(file(true, 1e15), "taobao"));
        assertThrows(ServiceException.class, () -> ExecutionListSource.read(file(true, 123.5), "taobao"));
    }
    @Test void acceptsTwentyThousandRecordsButRejectsOneMore() throws Exception {
        Path csv = dir.resolve("limit.csv");
        String rows = "商品ID\n" + "1072093000183\n".repeat(20000);
        Files.writeString(csv, rows);
        assertEquals(20000, ExecutionListSource.read(csv, "taobao").recordCount());
        Files.writeString(csv, rows + "1072093000183\n");
        assertThrows(ServiceException.class, () -> ExecutionListSource.read(csv, "taobao"));
    }
    @Test void storageAcceptsXlsButStillRejectsArbitraryPaths() {
        var files = new ExecutionListFiles(dir.toString());
        assertEquals(dir.resolve("12345678-1234-1234-1234-123456789012.xls"), files.resolve("12345678-1234-1234-1234-123456789012.xls"));
        assertThrows(ServiceException.class, () -> files.resolve("../source.xls"));
    }
}
