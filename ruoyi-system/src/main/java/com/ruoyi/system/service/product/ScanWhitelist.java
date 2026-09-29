package com.ruoyi.system.service.product;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 任务白名单匹配器，只放行被完整白名单内容覆盖的具体命中位置。
 * 每个任务编译一次；不处理手机号、二维码等独立检测项。
 */
public final class ScanWhitelist
{
    private final Map<String, List<Pattern>> patterns = new HashMap<>();

    /** 将任务快照中的规则编译为按过滤词索引的匹配模式。 */
    public ScanWhitelist(List<ScanModels.WhitelistRule> rules)
    {
        if (rules == null) return;
        for (var rule : rules)
        {
            if (rule.filterWord == null || rule.matchContent == null) continue;
            for (String line : rule.matchContent.split("\\R"))
            {
                String content = normalize(line.trim());
                if (content.isEmpty()) continue;
                String expression;
                if ("WORD".equals(rule.matchType))
                {
                    // 词汇按包含匹配，不限制前后字符；仍只豁免该片段覆盖的命中位置。
                    expression = Pattern.quote(content);
                }
                else if ("UNIT".equals(rule.matchType))
                {
                    int digits = 0;
                    while (digits < content.length() && content.charAt(digits) == '#') digits++;
                    if (digits == 0 || digits == content.length()) continue;
                    String unit = content.substring(digits);
                    // 无效配置不放行：#只能出现在开头代表数字，模板不得含空格、小数点。
                    if (unit.contains("#") || content.codePoints().anyMatch(Character::isWhitespace)
                            || unit.contains(".") || unit.contains(",")) continue;
                    expression = "(?<![a-z0-9_.,])" + "[0-9]{" + digits + "}"
                            + Pattern.quote(unit) + "(?![a-z0-9_])";
                }
                else continue;
                patterns.computeIfAbsent(normalize(rule.filterWord), key -> new ArrayList<>()).add(Pattern.compile(expression));
            }
        }
    }

    /** 检查同一过滤词的每一次出现；存在任何未被放行的位置就仍算命中。 */
    public boolean hasUnallowedHit(String text, String word)
    {
        int first = text.indexOf(word);
        if (first < 0) return false;
        List<Pattern> candidates = patterns.get(word);
        if (candidates == null || candidates.isEmpty()) return true;
        List<int[]> allowed = new ArrayList<>();
        for (Pattern pattern : candidates)
        {
            var matcher = pattern.matcher(text);
            // 包含片段可能重叠，例如ababa中的两个aba，需要同时保留覆盖范围。
            int searchFrom = 0;
            while (matcher.find(searchFrom))
            {
                allowed.add(new int[] { matcher.start(), matcher.end() });
                searchFrom = matcher.start() + 1;
            }
        }
        for (int start = first; start >= 0; start = text.indexOf(word, start + 1))
        {
            int end = start + word.length();
            boolean covered = false;
            for (int[] range : allowed)
                if (range[0] <= start && range[1] >= end) { covered = true; break; }
            if (!covered) return true;
        }
        return false;
    }

    private static String normalize(String value)
    {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
