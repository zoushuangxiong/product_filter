package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.ProductWhitelist;

/**
 * 白名单库Service接口
 *
 * @author product-filter
 * @date 2026-09-24
 */
public interface IProductWhitelistService
{
    /**
     * 查询白名单库列表
     *
     * @param productWhitelist 白名单查询条件
     * @return 白名单集合
     */
    public List<ProductWhitelist> selectProductWhitelistList(ProductWhitelist productWhitelist);

    /**
     * 查询白名单详细信息
     *
     * @param id 白名单主键
     * @return 白名单
     */
    public ProductWhitelist selectProductWhitelistById(Long id);

    /**
     * 新增白名单库
     *
     * @param productWhitelist 白名单
     * @return 结果
     */
    public int insertProductWhitelist(ProductWhitelist productWhitelist);

    /**
     * 修改白名单内容或启用状态，不修改过滤词及匹配方式
     *
     * @param productWhitelist 白名单
     * @return 结果
     */
    public int updateProductWhitelist(ProductWhitelist productWhitelist);

    /**
     * 删除白名单库
     *
     * @param id 白名单主键
     * @return 结果
     */
    public int deleteProductWhitelistById(Long id);

    /**
     * 追加白名单内容，合并去重且保留原启用状态
     *
     * @param productWhitelist 包含主键及待追加内容
     * @return 结果
     */
    public int appendProductWhitelist(ProductWhitelist productWhitelist);

    /**
     * 启用或禁用白名单
     *
     * @param productWhitelist 包含主键及状态
     * @return 结果
     */
    public int changeProductWhitelistStatus(ProductWhitelist productWhitelist);

}
