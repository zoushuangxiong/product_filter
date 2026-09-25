package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.ProductWhitelist;

/**
 * 白名单库Mapper接口
 *
 * @author product-filter
 * @date 2026-09-24
 */
public interface ProductWhitelistMapper
{
    /**
     * 查询启用白名单的完整内容，供新检测任务生成规则快照。
     *
     * @return 启用白名单集合
     */
    public List<ProductWhitelist> selectEnabledProductWhitelistList();

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
     * 根据过滤词与匹配方式查重
     *
     * @param productWhitelist 包含标准化过滤词及匹配方式
     * @return 已有白名单
     */
    public ProductWhitelist selectProductWhitelistByKey(ProductWhitelist productWhitelist);

    /**
     * 锁定白名单记录，串行执行追加、修改和删除
     *
     * @param id 白名单主键
     * @return 白名单
     */
    public ProductWhitelist selectProductWhitelistForUpdate(Long id);

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

}
