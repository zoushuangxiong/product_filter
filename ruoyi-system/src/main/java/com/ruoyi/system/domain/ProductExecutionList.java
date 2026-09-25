package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 执行清单对象 product_execution_list，表格内容保存在文件系统
 *
 * @author ruoyi
 * @date 2026-09-23
 */
public class ProductExecutionList extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 清单主键 */
    private Long id;

    /** 商品平台：taobao 或 1688 */
    private String platform;

    /** 清单名称，使用完整文件名，全局唯一 */
    private String fileName;

    /** 原始文件相对路径，仅服务端使用 */
    @JsonIgnore
    private String filePath;

    /** 原始文件去重后的商品数 */
    private Integer productCount;

    /** 原始文件记录数，包含重复商品 */
    private Integer recordCount;

    /** 当前账号过滤结果的相对路径，仅服务端使用 */
    @JsonIgnore
    private String filteredFilePath;

    /** 过滤后去重商品数，未过滤时为空 */
    private Integer filteredProductCount;

    /** 过滤后记录数，未过滤时为空 */
    private Integer filteredRecordCount;

    /** 当前账号最近一次过滤时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date filteredTime;

    /** 过滤快照版本，防止提交过期的过滤结果 */
    private String filterVersion;

    /** 过滤结果所属用户，服务端设置 */
    @JsonIgnore
    private Long filterOwnerId;

    /** 新增时上传的原始文件，不写入数据库 */
    @JsonIgnore
    private MultipartFile file;

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId()
    {
        return id;
    }

    public void setPlatform(String platform)
    {
        this.platform = platform;
    }

    public String getPlatform()
    {
        return platform;
    }

    public void setFileName(String fileName)
    {
        this.fileName = fileName;
    }

    public String getFileName()
    {
        return fileName;
    }

    public void setFilePath(String filePath)
    {
        this.filePath = filePath;
    }

    public String getFilePath()
    {
        return filePath;
    }

    public void setProductCount(Integer productCount)
    {
        this.productCount = productCount;
    }

    public Integer getProductCount()
    {
        return productCount;
    }

    public void setRecordCount(Integer recordCount)
    {
        this.recordCount = recordCount;
    }

    public Integer getRecordCount()
    {
        return recordCount;
    }

    public void setFilteredFilePath(String filteredFilePath)
    {
        this.filteredFilePath = filteredFilePath;
    }

    public String getFilteredFilePath()
    {
        return filteredFilePath;
    }

    public void setFilteredProductCount(Integer filteredProductCount)
    {
        this.filteredProductCount = filteredProductCount;
    }

    public Integer getFilteredProductCount()
    {
        return filteredProductCount;
    }

    public void setFilteredRecordCount(Integer filteredRecordCount)
    {
        this.filteredRecordCount = filteredRecordCount;
    }

    public Integer getFilteredRecordCount()
    {
        return filteredRecordCount;
    }

    public void setFilteredTime(Date filteredTime)
    {
        this.filteredTime = filteredTime;
    }

    public Date getFilteredTime()
    {
        return filteredTime;
    }

    public void setFilterVersion(String filterVersion)
    {
        this.filterVersion = filterVersion;
    }

    public String getFilterVersion()
    {
        return filterVersion;
    }

    public void setFilterOwnerId(Long filterOwnerId)
    {
        this.filterOwnerId = filterOwnerId;
    }

    public Long getFilterOwnerId()
    {
        return filterOwnerId;
    }

    public void setFile(MultipartFile file)
    {
        this.file = file;
    }

    public MultipartFile getFile()
    {
        return file;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("platform", getPlatform())
            .append("fileName", getFileName())
            .append("recordCount", getRecordCount())
            .append("productCount", getProductCount())
            .append("filteredRecordCount", getFilteredRecordCount())
            .append("filteredProductCount", getFilteredProductCount())
            .append("remark", getRemark())
            .toString();
    }
}
