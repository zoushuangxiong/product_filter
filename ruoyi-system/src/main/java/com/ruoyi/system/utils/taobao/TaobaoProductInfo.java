package com.ruoyi.system.utils.taobao;

import java.util.List;

/** 商品快照。图片字段是远程 URL，不会自动下载图片。 */
public final class TaobaoProductInfo
{
    private final String title;
    private final List<String> mainImages;
    private final String priceText;
    private final String categoryName, shopUrl, sales, commentCount;
    private final List<String> detailImages;

    TaobaoProductInfo(String title, List<String> mainImages,
            String priceText, List<String> detailImages,
            String categoryName, String shopUrl, String sales, String commentCount)
    {
        this.categoryName = categoryName; this.shopUrl = shopUrl; this.sales = sales; this.commentCount = commentCount;
        this.title = title;
        this.mainImages = List.copyOf(mainImages);
        this.priceText = priceText;
        this.detailImages = List.copyOf(detailImages);
    }

    public String getCategoryName() { return categoryName; }
    public String getShopUrl() { return shopUrl; }
    public String getSales() { return sales; }
    public String getCommentCount() { return commentCount; }

    public String getTitle()
    {
        return title;
    }

    public List<String> getMainImages()
    {
        return mainImages;
    }

    /** 展示价格，可能是区间；不代表用户最终结算价。 */
    public String getPriceText()
    {
        return priceText;
    }

    public List<String> getDetailImages()
    {
        return detailImages;
    }

}
