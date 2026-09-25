package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 商品信息缓存对象 product_info_cache，不保存检测规则和检测结论。
 *
 * @author product-filter
 * @date 2026-09-25
 */
public class ProductInfoCache
{
    /** 商品平台 */
    private String platform;

    /** 商品ID */
    private String itemId;

    /** 接口返回的原始标题 */
    private String title;

    /** 全部主图链接，JSON数组 */
    private String mainImages;

    /** 全部详情图链接，JSON数组 */
    private String detailImages;

    /** 价格文本 */
    private String priceText;

    /** 类目名称 */
    private String categoryName;

    /** 店铺链接 */
    private String shopUrl;

    /** 销量文本 */
    private String sales;

    /** 评论数文本 */
    private String commentCount;

    /** 接口成功获取时间 */
    private Date fetchedTime;

    public String getPlatform()
    {
        return platform;
    }

    public void setPlatform(String platform)
    {
        this.platform = platform;
    }

    public String getItemId()
    {
        return itemId;
    }

    public void setItemId(String itemId)
    {
        this.itemId = itemId;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getMainImages()
    {
        return mainImages;
    }

    public void setMainImages(String mainImages)
    {
        this.mainImages = mainImages;
    }

    public String getDetailImages()
    {
        return detailImages;
    }

    public void setDetailImages(String detailImages)
    {
        this.detailImages = detailImages;
    }

    public String getPriceText()
    {
        return priceText;
    }

    public void setPriceText(String priceText)
    {
        this.priceText = priceText;
    }

    public String getCategoryName()
    {
        return categoryName;
    }

    public void setCategoryName(String categoryName)
    {
        this.categoryName = categoryName;
    }

    public String getShopUrl()
    {
        return shopUrl;
    }

    public void setShopUrl(String shopUrl)
    {
        this.shopUrl = shopUrl;
    }

    public String getSales()
    {
        return sales;
    }

    public void setSales(String sales)
    {
        this.sales = sales;
    }

    public String getCommentCount()
    {
        return commentCount;
    }

    public void setCommentCount(String commentCount)
    {
        this.commentCount = commentCount;
    }

    public Date getFetchedTime()
    {
        return fetchedTime;
    }

    public void setFetchedTime(Date fetchedTime)
    {
        this.fetchedTime = fetchedTime;
    }

}
