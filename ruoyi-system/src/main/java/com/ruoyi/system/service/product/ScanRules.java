package com.ruoyi.system.service.product;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.ruoyi.common.exception.ServiceException;

/** 商品链接校验、文字命中规则与导出条件；前后端展示不改变这里的判定。 */
public final class ScanRules
{
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    private ScanRules() { }

    /** 支持数字 ID 或所选平台的完整链接；数字 ID 本身无法判断所属平台。 */
    public static String itemId(String input, String platform)
    {
        if (!Set.of("taobao", "1688").contains(platform == null ? "" : platform))
            throw new ServiceException("请先选择商品平台：淘宝/天猫或1688");
        if (input == null) throw new ServiceException("商品 ID 不能为空");
        String value = input.trim();
        if (value.matches("[1-9][0-9]{0,19}")) return value;
        try
        {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if ("1688".equals(platform))
            {
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getUserInfo() != null || !"detail.1688.com".equalsIgnoreCase(host)
                        || !uri.getPath().matches("/offer/[1-9][0-9]{0,19}\\.html"))
                    throw new IllegalArgumentException();
                return uri.getPath().substring(7, uri.getPath().length() - 5);
            }
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host == null || uri.getUserInfo() != null
                    || !Set.of("item.taobao.com", "detail.tmall.com", "detail.m.tmall.com", "h5.m.taobao.com").contains(host.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException();
            for (String pair : uri.getRawQuery().split("&"))
            {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "id".equals(parts[0]))
                {
                    String id = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    if (id.matches("[1-9][0-9]{0,19}")) return id;
                }
            }
        }
        catch (RuntimeException ignored) { }
        throw new ServiceException("商品链接与所选平台不匹配，或链接格式不受支持；1688 请使用 detail.1688.com/offer/商品ID.html，淘宝/天猫请使用完整商品链接");
    }

    /** 按换行拆词，去除首尾空白和重复词；逗号、顿号和词内空格不作分隔。 */
    public static List<String> words(String text)
    {
        if (text == null) throw new ServiceException("词库内容不能为空");
        Set<String> result = new LinkedHashSet<>();
        for (String word : text.split("\\R"))
        {
            String w = word.trim();
            if (w.length() > 100) throw new ServiceException("单条过滤词不能超过 100 字符");
            if (!w.isEmpty()) result.add(w);
        }
        if (result.size() > 10_000) throw new ServiceException("每份词库最多 10,000 条");
        return List.copyOf(result);
    }

    /**
     * 匹配标题过滤词，统一全半角及大小写后，按词库顺序命中一个即停止。
     *
     * @param text 商品标题
     * @param words 标题过滤词
     * @return 首个命中词；未命中时返回空集合
     */
    public static List<String> matchTitle(String text, List<String> words)
    {
        String normalized = normalize(text);
        for (String word : words)
        {
            if (normalized.contains(normalize(word))) return List.of(word);
        }
        return List.of();
    }

    /** 统一全半角及大小写后进行包含匹配；手机号必须是独立的 11 位数字。 */
    public static List<String> match(String text, List<String> words, boolean phones)
    {
        String normalized = normalize(text);
        Set<String> hits = new LinkedHashSet<>();
        for (String word : words) if (normalized.contains(normalize(word))) hits.add(word);
        if (phones)
        {
            var matcher = PHONE.matcher(normalized);
            while (matcher.find()) hits.add("手机号:" + matcher.group());
        }
        return List.copyOf(hits);
    }

    /** 每个任务只规范化一次词库，保留原词和顺序以维持命中展示与标题提前结束语义。 */
    public static PreparedWords prepare(String text) { return new PreparedWords(words(text)); }

    public static final class PreparedWords
    {
        private final List<String> originals;
        private final List<String> normalized;

        private PreparedWords(List<String> words)
        {
            originals = List.copyOf(words);
            normalized = words.stream().map(ScanRules::normalize).toList();
        }

        /** 标题只取首个命中；图片保留全部命中及手机号。 */
        public List<String> match(String text, boolean phones, boolean firstOnly)
        {
            return match(text, phones, firstOnly, null);
        }

        /** 过滤词命中后逐位置校验白名单；仅全部位置被放行时才忽略该词。 */
        public List<String> match(String text, boolean phones, boolean firstOnly, ScanWhitelist whitelist)
        {
            String value = normalize(text);
            Set<String> hits = new LinkedHashSet<>();
            for (int i = 0; i < normalized.size(); i++)
            {
                if (!value.contains(normalized.get(i))) continue;
                if (whitelist != null && !whitelist.hasUnallowedHit(value, normalized.get(i))) continue;
                if (firstOnly) return List.of(originals.get(i));
                hits.add(originals.get(i));
            }
            if (phones)
            {
                var matcher = PHONE.matcher(value);
                while (matcher.find()) hits.add("手机号:" + matcher.group());
            }
            return List.copyOf(hits);
        }
    }

    private static String normalize(String s)
    {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /** 检测完成且内容完整，未被人工排除，并且无命中或已人工信任，才能导出通过项。 */
    public static boolean exportable(ScanModels.Product product)
    {
        return "DONE".equals(product.state) && !product.incomplete && !"REJECTED".equals(product.review)
                && ("CLEAR".equals(product.verdict) || "TRUSTED".equals(product.review));
    }

    /** 转义 CSV 引号；对公式起始字符加文本前缀，防止表格软件执行公式。 */
    public static String csv(String text)
    {
        String value = text == null ? "" : text;
        String trimmed = value.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
