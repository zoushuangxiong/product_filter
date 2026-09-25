package com.ruoyi.system.service.product;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.ruoyi.common.exception.ServiceException;

/** 解析导入 CSV，保留列值及顺序，供固定模板导出使用。 */
final class ScanCsvSource
{
    // raw 用于区分空行，cells 保留解码后的单元格内容。
    record Row(String raw, List<String> cells) { }
    record Source(List<Row> rows, int column, boolean header)
    {
        List<String> items(String platform)
        {
            return rows.stream().skip(header ? 1 : 0).filter(r -> !r.raw().isBlank())
                    .map(r -> ScanRules.itemId(r.cells().get(column).trim(), platform)).toList();
        }
    }
    /** 按商品ID收集导入标题；保留重复商品的不同标题，任一标题命中即可结束检测。 */
    static java.util.Map<String, List<String>> titles(String text, String platform)
    {
        java.util.Map<String, List<String>> titles = new java.util.HashMap<>();
        if (text == null || text.isBlank()) return titles;
        Source source = parse(text);
        if (!source.header()) return titles;
        List<String> headers = source.rows().get(0).cells();
        int titleColumn = -1;
        for (int i = 0; i < headers.size(); i++)
            if (Set.of("商品标题", "标题", "商品名称", "title").contains(headers.get(i).trim()))
            { titleColumn = i; break; }
        if (titleColumn < 0) return titles;
        for (Row row : source.rows().subList(1, source.rows().size()))
        {
            if (row.raw().isBlank() || row.cells().size() <= titleColumn) continue;
            String title = row.cells().get(titleColumn).trim();
            if (title.isEmpty()) continue;
            String id = ScanRules.itemId(row.cells().get(source.column()).trim(), platform);
            titles.computeIfAbsent(id, key -> new ArrayList<>()).add(title);
        }
        return titles;
    }

    /** 支持 BOM、带引号的逗号/换行及双引号转义；识别商品列，供导入校验与导出复用。 */
    static Source parse(String text)
    {
        if (text.getBytes(StandardCharsets.UTF_8).length > 20 * 1024 * 1024) throw new ServiceException("导入 CSV 不能超过 20 MB");
        String prefix = text.startsWith("\uFEFF") ? "\uFEFF" : "";
        int start = prefix.length();
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = start; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '"')
            {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            }
            else if (!quoted && c == ',') { cells.add(cell.toString()); cell.setLength(0); }
            else if (!quoted && (c == '\r' || c == '\n'))
            {
                cells.add(cell.toString()); cell.setLength(0);
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                rows.add(new Row(text.substring(start, i + 1), List.copyOf(cells)));
                cells.clear(); start = i + 1;
            }
            else cell.append(c);
        }
        if (quoted) throw new ServiceException("CSV 引号未闭合");
        if (start < text.length()) { cells.add(cell.toString()); rows.add(new Row(text.substring(start), List.copyOf(cells))); }
        if (rows.isEmpty()) throw new ServiceException("CSV 没有商品数据");
        int column = 0; boolean header = false;
        for (int i = 0; i < rows.get(0).cells().size(); i++)
            if (Set.of("商品ID", "商品id", "itemId", "item_id", "商品链接", "商品链接（必填）").contains(rows.get(0).cells().get(i).trim()))
            { column = i; header = true; break; }
        for (Row row : rows)
            if (!row.raw().isBlank() && row.cells().size() <= column) throw new ServiceException("CSV 数据行缺少商品列");
        return new Source(rows, column, header);
    }
}
