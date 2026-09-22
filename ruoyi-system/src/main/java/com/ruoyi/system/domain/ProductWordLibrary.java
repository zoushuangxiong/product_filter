package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 过滤词库对象 product_word_library
 *
 * @author product-filter
 * @date 2026-09-22
 */
public class ProductWordLibrary extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 词库主键 */
    private Long id;

    /** 所属登录用户ID，词库不跨用户共享 */
    private Long ownerId;

    /** 用户在页面选择时看到的名称，单个用户内唯一 */
    private String name;

    /** 标题过滤词，按换行分隔 */
    private String titleWords;

    /** 图片文字过滤词，按换行分隔 */
    private String imageWords;

    /** 是否额外检测图片中的11位手机号 */
    private Boolean detectPhones;

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId()
    {
        return id;
    }

    public void setOwnerId(Long ownerId)
    {
        this.ownerId = ownerId;
    }

    public Long getOwnerId()
    {
        return ownerId;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    public void setTitleWords(String titleWords)
    {
        this.titleWords = titleWords;
    }

    public String getTitleWords()
    {
        return titleWords;
    }

    public void setImageWords(String imageWords)
    {
        this.imageWords = imageWords;
    }

    public String getImageWords()
    {
        return imageWords;
    }

    public void setDetectPhones(Boolean detectPhones)
    {
        this.detectPhones = detectPhones;
    }

    public Boolean getDetectPhones()
    {
        return detectPhones;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("ownerId", getOwnerId())
            .append("name", getName())
            .append("titleWords", getTitleWords())
            .append("imageWords", getImageWords())
            .append("detectPhones", getDetectPhones())
            .toString();
    }
}
