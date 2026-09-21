package com.ruoyi.system.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/** 当前用户保存的检测词库；检测任务使用规则副本，不受词库后续修改影响。 */
public class ProductWordLibrary extends BaseEntity
{
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long ownerId;
    private String name;
    private String titleWords;
    private String imageWords;
    private Boolean detectPhones;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitleWords() { return titleWords; }
    public void setTitleWords(String titleWords) { this.titleWords = titleWords; }

    public String getImageWords() { return imageWords; }
    public void setImageWords(String imageWords) { this.imageWords = imageWords; }

    public Boolean getDetectPhones() { return detectPhones; }
    public void setDetectPhones(Boolean detectPhones) { this.detectPhones = detectPhones; }
}
