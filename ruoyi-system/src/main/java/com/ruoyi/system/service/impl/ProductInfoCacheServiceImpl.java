package com.ruoyi.system.service.impl;

import java.util.Date;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.domain.ProductInfoCache;
import com.ruoyi.system.mapper.ProductInfoCacheMapper;
import com.ruoyi.system.service.IProductInfoCacheService;
import com.ruoyi.system.utils.taobao.TaobaoProductInfo;

/**
 * 商品信息缓存Service业务层处理，数据库内容长期保留，服务重启后仍可复用。
 *
 * @author product-filter
 * @date 2026-09-25
 */
@Service
public class ProductInfoCacheServiceImpl implements IProductInfoCacheService
{
    @Autowired
    private ProductInfoCacheMapper productInfoCacheMapper;

    /** 固定数量的锁，避免同一服务内多个任务同时获取相同商品；不随商品数量增长。 */
    private final Object[] locks = new Object[256];

    public ProductInfoCacheServiceImpl()
    {
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    /**
     * 先查数据库，未命中时串行获取并保存；不缓存失败或空结果，不读取旧检测结论。
     *
     * @param platform 商品平台
     * @param itemId 商品ID
     * @param fetcher 第三方获取函数
     * @return 商品信息
     */
    @Override
    public TaobaoProductInfo getOrFetch(String platform, String itemId, Supplier<TaobaoProductInfo> fetcher)
    {
        ProductInfoCache key = new ProductInfoCache();
        key.setPlatform(platform); key.setItemId(itemId);
        ProductInfoCache saved = productInfoCacheMapper.selectProductInfoCache(key);
        if (saved != null) return toProduct(saved);
        synchronized (locks[Math.floorMod((platform + ":" + itemId).hashCode(), locks.length)])
        {
            // 等待期间可能已有任务写入，必须二次查询后再决定是否调用付费接口。
            saved = productInfoCacheMapper.selectProductInfoCache(key);
            if (saved != null) return toProduct(saved);
            TaobaoProductInfo product = fetcher.get();
            if (product == null) return null;
            key.setTitle(product.getTitle());
            key.setMainImages(JSON.toJSONString(product.getMainImages()));
            key.setDetailImages(JSON.toJSONString(product.getDetailImages()));
            key.setPriceText(product.getPriceText()); key.setCategoryName(product.getCategoryName());
            key.setShopUrl(product.getShopUrl()); key.setSales(product.getSales());
            key.setCommentCount(product.getCommentCount()); key.setFetchedTime(new Date());
            productInfoCacheMapper.insertProductInfoCache(key);
            return product;
        }
    }

    /** 恢复全部接口字段，图片链接保持原始顺序，不使用任务中可能被导入标题覆盖的内容。 */
    private TaobaoProductInfo toProduct(ProductInfoCache item)
    {
        return new TaobaoProductInfo(item.getTitle(), JSON.parseArray(item.getMainImages(), String.class),
                item.getPriceText(), JSON.parseArray(item.getDetailImages(), String.class),
                item.getCategoryName(), item.getShopUrl(), item.getSales(), item.getCommentCount());
    }
}
