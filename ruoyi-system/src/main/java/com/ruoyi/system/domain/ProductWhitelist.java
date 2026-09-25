package com.ruoyi.system.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 白名单库对象 product_whitelist
 *
 * @author product-filter
 * @date 2026-09-24
 */
public class ProductWhitelist extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 白名单主键 */
    private Long id;

    /** 过滤词 */
    private String filterWord;

    /** 过滤词标准化查重键，不对外展示 */
    @JsonIgnore
    private String wordKey;

    /** 匹配方式：UNIT计量单位，WORD词汇 */
    private String matchType;

    /** 白名单匹配内容，每行一项 */
    private String matchContent;

    /** 去重后的匹配内容数量 */
    private Integer matchCount;

    /** 状态：0启用，1禁用 */
    private String status;

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId()
    {
        return id;
    }

    public void setFilterWord(String filterWord)
    {
        this.filterWord = filterWord;
    }

    public String getFilterWord()
    {
        return filterWord;
    }

    public void setWordKey(String wordKey)
    {
        this.wordKey = wordKey;
    }

    public String getWordKey()
    {
        return wordKey;
    }

    public void setMatchType(String matchType)
    {
        this.matchType = matchType;
    }

    public String getMatchType()
    {
        return matchType;
    }

    public void setMatchContent(String matchContent)
    {
        this.matchContent = matchContent;
    }

    public String getMatchContent()
    {
        return matchContent;
    }

    public void setMatchCount(Integer matchCount)
    {
        this.matchCount = matchCount;
    }

    public Integer getMatchCount()
    {
        return matchCount;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("filterWord", getFilterWord())
            .append("matchType", getMatchType())
            .append("matchContent", getMatchContent())
            .append("matchCount", getMatchCount())
            .append("status", getStatus())
            .toString();
    }
}
