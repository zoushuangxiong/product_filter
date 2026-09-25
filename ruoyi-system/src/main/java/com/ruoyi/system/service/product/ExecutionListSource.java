package com.ruoyi.system.service.product;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.util.XMLHelper;
import org.xml.sax.InputSource;
import com.ruoyi.common.exception.ServiceException;

/**
 * 清单文件读取与过滤：CSV 按字符流读取，Excel 按行解析首张工作表，不创建整本工作簿
 *
 * @author ruoyi
 * @date 2026-09-23
 */
public final class ExecutionListSource
{
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> HEADERS = Set.of("商品ID", "商品id", "itemId", "item_id", "商品链接", "商品链接（必填）");

    private ExecutionListSource() { }

    /** 表格快照保留全部列、重复行及顺序；只在导入、过滤或创建任务时读取。 */
    public record Table(List<List<String>> rows, List<String> items, String csv)
    {
        public int recordCount() { return items.size(); }
        public int productCount() { return new HashSet<>(items).size(); }
    }

    /**
     * 读取并校验原始表格
     *
     * @param path 服务端保存的文件路径
     * @param platform 所属平台
     * @return 包含有效商品记录的表格
     */
    public static Table read(Path path, String platform)
    {
        try
        {
            if (!Files.isRegularFile(path)) throw new ServiceException("清单文件不存在，请检查服务器文件目录");
            if (Files.size(path) > MAX_BYTES) throw new ServiceException("文件不能超过20 MB");
            List<List<String>> rows;
            if (path.toString().endsWith(".xlsx")) rows = excel(path);
            else
            {
                try { rows = csv(path, StandardCharsets.UTF_8); }
                catch (CharacterCodingException e) { rows = csv(path, Charset.forName("GB18030")); }
            }
            return table(rows, platform, false);
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("表格读取失败，请检查文件格式、编码和内容"); }
    }

    /**
     * 过滤同平台已获取成功的商品，保留其余记录的原始列及重复行
     *
     * @param source 原始文件的快照
     * @param checked 已检测商品ID集合
     * @return 过滤后的表格，允许仅剩表头
     */
    public static Table filter(Table source, Set<String> checked)
    {
        List<List<String>> rows = new ArrayList<>();
        List<String> items = new ArrayList<>();
        rows.add(source.rows().get(0));
        for (int i = 0; i < source.items().size(); i++)
        {
            if (!checked.contains(source.items().get(i)))
            {
                rows.add(source.rows().get(i + 1));
                items.add(source.items().get(i));
            }
        }
        return new Table(rows, items, encode(rows));
    }

    /** 流式拆分 CSV，支持引号内的逗号、换行与双引号转义。 */
    private static List<List<String>> csv(Path path, Charset charset) throws Exception
    {
        List<List<String>> rows = new ArrayList<>();
        try (PushbackReader reader = new PushbackReader(new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT))), 1))
        {
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false, first = true;
            int value;
            while ((value = reader.read()) != -1)
            {
                char c = (char) value;
                if (first) { first = false; if (c == '\uFEFF') continue; }
                if (c == '"')
                {
                    if (quoted)
                    {
                        int next = reader.read();
                        if (next == '"') cell.append('"');
                        else { quoted = false; if (next != -1) reader.unread(next); }
                    }
                    else quoted = true;
                }
                else if (!quoted && c == ',') { row.add(cell.toString()); cell.setLength(0); }
                else if (!quoted && (c == '\n' || c == '\r'))
                {
                    if (c == '\r') { int next = reader.read(); if (next != '\n' && next != -1) reader.unread(next); }
                    row.add(cell.toString()); addRow(rows, row); row = new ArrayList<>(); cell.setLength(0);
                }
                else cell.append(c);
                if (row.size() > 256 || cell.length() > MAX_BYTES) throw new ServiceException("表格列数或单元格内容过大");
            }
            if (quoted) throw new ServiceException("CSV 引号未闭合");
            if (!row.isEmpty() || cell.length() > 0) { row.add(cell.toString()); addRow(rows, row); }
        }
        return rows;
    }

    /** Excel SAX 解析，最多保留一万条记录，并限制解压后的文本量。 */
    private static List<List<String>> excel(Path path) throws Exception
    {
        List<List<String>> rows = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ))
        {
            XSSFReader workbook = new XSSFReader(pkg);
            var sheets = workbook.getSheetsData();
            if (!sheets.hasNext()) throw new ServiceException("Excel 没有工作表");
            double[] numericCell = {Double.NaN};
            DataFormatter formatter = new DataFormatter()
            {
                @Override
                public String formatRawCellContents(double value, int index, String format, boolean use1904)
                {
                    numericCell[0] = value;
                    // 常规数字和科学计数显示格式使用完整数值，防止13位商品ID被舍入。
                    if ("General".equalsIgnoreCase(format) || (format != null && format.toUpperCase().contains("E+")))
                        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
                    return super.formatRawCellContents(value, index, format, use1904);
                }
            };
            XSSFSheetXMLHandler.SheetContentsHandler handler = new XSSFSheetXMLHandler.SheetContentsHandler()
            {
                private List<String> row;
                private long characters;
                private int itemColumn = -1;
                public void startRow(int rowNum) { row = new ArrayList<>(); }
                public void endRow(int rowNum)
                {
                    if (rows.isEmpty())
                        for (int i = 0; i < row.size(); i++) if (HEADERS.contains(row.get(i).trim())) { itemColumn = i; break; }
                    addRow(rows, row);
                }
                public void cell(String reference, String value, XSSFComment comment)
                {
                    int column = new CellReference(reference).getCol();
                    if (column == itemColumn && !Double.isNaN(numericCell[0])
                        && (numericCell[0] >= 1e15 || numericCell[0] != Math.rint(numericCell[0])))
                        throw new ServiceException("Excel 数字商品ID过长或不是整数，请用文本格式填写完整ID");
                    numericCell[0] = Double.NaN;
                    if (column >= 256) throw new ServiceException("表格最多支持256列");
                    characters += value == null ? 0 : value.length();
                    if (characters > MAX_BYTES) throw new ServiceException("Excel 表格内容过大");
                    while (row.size() <= column) row.add("");
                    row.set(column, value == null ? "" : value);
                }
            };
            var parser = XMLHelper.newXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(workbook.getStylesTable(), null,
                new ReadOnlySharedStringsTable(pkg), handler, formatter, false));
            try (InputStream sheet = sheets.next()) { parser.parse(new InputSource(sheet)); }
        }
        return rows;
    }

    /** 空行不计数，限制有效记录，避免稀疏工作表占用无界内存。 */
    private static void addRow(List<List<String>> rows, List<String> row)
    {
        if (row.stream().allMatch(String::isBlank)) return;
        if (rows.size() >= 10001) throw new ServiceException("每份清单最多10,000条商品记录");
        rows.add(List.copyOf(row));
    }

    /** 校验表头、平台和商品列，商品链接与数字ID统一为同一去重键。 */
    private static Table table(List<List<String>> rows, String platform, boolean allowEmpty)
    {
        if (rows.isEmpty()) throw new ServiceException("文件没有表头或商品数据");
        int column = -1;
        for (int i = 0; i < rows.get(0).size(); i++) if (HEADERS.contains(rows.get(0).get(i).trim())) { column = i; break; }
        if (column < 0) throw new ServiceException("文件必须包含表头，并包含“商品链接”或“商品ID”列");
        List<String> items = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++)
        {
            List<String> row = rows.get(i);
            if (column >= row.size() || row.get(column).isBlank()) throw new ServiceException("第" + (i + 1) + "行缺少商品ID或链接");
            try { items.add(ScanRules.itemId(row.get(column), platform)); }
            catch (ServiceException e) { throw new ServiceException("第" + (i + 1) + "行：" + e.getMessage()); }
        }
        if (!allowEmpty && items.isEmpty()) throw new ServiceException("文件没有商品数据");
        return new Table(rows, items, encode(rows));
    }

    /** 规范化为 UTF-8 CSV，保留每列内容、重复记录和原始顺序，供任务导出使用。 */
    private static String encode(List<List<String>> rows)
    {
        StringBuilder csv = new StringBuilder();
        for (List<String> row : rows)
        {
            for (int i = 0; i < row.size(); i++)
            {
                if (i > 0) csv.append(',');
                csv.append('"').append(row.get(i).replace("\"", "\"\"")).append('"');
            }
            csv.append("\r\n");
        }
        if (csv.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ServiceException("转换后的表格内容不能超过20 MB");
        return csv.toString();
    }
}
