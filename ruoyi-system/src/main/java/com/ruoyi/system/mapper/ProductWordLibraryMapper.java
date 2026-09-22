package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.ProductWordLibrary;

/**
 * 过滤词库Mapper接口
 *
 * @author product-filter
 * @date 2026-09-22
 */
public interface ProductWordLibraryMapper
{
    /**
     * 查询过滤词库列表
     *
     * @param productWordLibrary 过滤词库查询条件
     * @return 过滤词库集合
     */
    public List<ProductWordLibrary> selectProductWordLibraryList(ProductWordLibrary productWordLibrary);

    /**
     * 查询过滤词库
     *
     * @param id 过滤词库主键
     * @return 过滤词库
     */
    public ProductWordLibrary selectProductWordLibraryById(Long id);

    /**
     * 新增过滤词库
     *
     * @param productWordLibrary 过滤词库
     * @return 结果
     */
    public int insertProductWordLibrary(ProductWordLibrary productWordLibrary);

    /**
     * 修改过滤词库
     *
     * @param productWordLibrary 过滤词库
     * @return 结果
     */
    public int updateProductWordLibrary(ProductWordLibrary productWordLibrary);

    /**
     * 删除过滤词库
     *
     * @param productWordLibrary 过滤词库信息，包含主键
     * @return 结果
     */
    public int deleteProductWordLibraryById(ProductWordLibrary productWordLibrary);
}
