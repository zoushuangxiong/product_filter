package com.ruoyi.system.service;

import java.util.function.Supplier;
import com.ruoyi.system.utils.taobao.TaobaoProductInfo;

/**
 * 商品信息缓存Service接口
 *
 * @author product-filter
 * @date 2026-09-25
 */
public interface IProductInfoCacheService
{
    /**
     * 优先复用数据库中的商品信息，未保存时调用获取函数并持久化。
     *
     * @param platform 商品平台
     * @param itemId 商品ID
     * @param fetcher 实际调用接口的函数；额度不足时返回null，异常时不写缓存
     * @return 商品信息；null表示本次未能获取
     */
    public TaobaoProductInfo getOrFetch(String platform, String itemId, Supplier<TaobaoProductInfo> fetcher);
}
