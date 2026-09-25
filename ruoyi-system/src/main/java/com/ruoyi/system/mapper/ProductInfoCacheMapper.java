package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.ProductInfoCache;

/**
 * 商品信息缓存Mapper接口
 *
 * @author product-filter
 * @date 2026-09-25
 */
public interface ProductInfoCacheMapper
{
    /**
     * 按平台和商品ID查询完整的商品信息。
     *
     * @param productInfoCache 包含平台及商品ID
     * @return 已保存的商品信息
     */
    public ProductInfoCache selectProductInfoCache(ProductInfoCache productInfoCache);

    /**
     * 保存成功获取的商品信息，唯一键重复时保留已有记录。
     *
     * @param productInfoCache 商品信息
     * @return 结果
     */
    public int insertProductInfoCache(ProductInfoCache productInfoCache);
}
