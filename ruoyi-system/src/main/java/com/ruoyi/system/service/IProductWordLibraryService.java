package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.ProductWordLibrary;

/**
 * 过滤词库Service接口
 *
 * @author product-filter
 * @date 2026-09-22
 */
public interface IProductWordLibraryService
{
    /**
     * 查询过滤词库集合
     *
     * @param productWordLibrary 过滤词库查询条件
     * @return 词库集合
     */
    public List<ProductWordLibrary> selectProductWordLibraryList(ProductWordLibrary productWordLibrary);

    /**
     * 通过词库ID查询词库信息
     *
     * @param ownerId 当前登录用户ID
     * @param id 词库ID
     * @return 词库信息
     */
    public ProductWordLibrary selectProductWordLibraryById(Long ownerId, Long id);

    /**
     * 新增保存过滤词库
     *
     * @param ownerId 当前登录用户ID
     * @param username 当前登录用户名
     * @param productWordLibrary 词库信息
     * @return 结果
     */
    public int insertProductWordLibrary(Long ownerId, String username, ProductWordLibrary productWordLibrary);

    /**
     * 修改保存过滤词库
     *
     * @param ownerId 当前登录用户ID
     * @param username 当前登录用户名
     * @param productWordLibrary 词库信息
     * @return 结果
     */
    public int updateProductWordLibrary(Long ownerId, String username, ProductWordLibrary productWordLibrary);

    /**
     * 删除过滤词库
     *
     * @param ownerId 当前登录用户ID
     * @param id 词库ID
     * @return 结果
     */
    public int deleteProductWordLibraryById(Long ownerId, Long id);
}
