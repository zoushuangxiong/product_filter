package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.ProductExecutionList;

/**
 * 执行清单Mapper接口
 *
 * @author ruoyi
 * @date 2026-09-23
 */
public interface ProductExecutionListMapper
{

    /**
     * 查询执行清单
     *
     * @param id 执行清单主键
     * @return 执行清单
     */
    public ProductExecutionList selectProductExecutionListById(Long id);

    /**
     * 查询执行清单列表
     *
     * @param productExecutionList 执行清单查询条件
     * @return 执行清单集合
     */
    public List<ProductExecutionList> selectProductExecutionListList(ProductExecutionList productExecutionList);

    /**
     * 新增执行清单
     *
     * @param productExecutionList 执行清单
     * @return 结果
     */
    public int insertProductExecutionList(ProductExecutionList productExecutionList);

    /**
     * 修改执行清单
     *
     * @param productExecutionList 执行清单
     * @return 结果
     */
    public int updateProductExecutionList(ProductExecutionList productExecutionList);

    /**
     * 批量删除执行清单
     *
     * @param ids 需要删除的执行清单主键
     * @return 结果
     */
    public int deleteProductExecutionListByIds(Long[] ids);

    /**
     * 删除执行清单信息
     *
     * @param id 执行清单主键
     * @return 结果
     */
    public int deleteProductExecutionListById(Long id);

    /**
     * 按文件名查询清单主键，配合唯一索引防止并发重复保存
     *
     * @param fileName 原始文件名
     * @return 已存在的清单主键
     */
    public Long selectProductExecutionListIdByFileName(String fileName);
    /**
     * 锁定清单，供过滤、启动任务及删除共用
     * @param id 清单主键
     * @return 清单
     */
    public ProductExecutionList selectProductExecutionListForUpdate(Long id);

    /**
     * 查询当前用户的过滤快照
     * @param productExecutionList 清单主键及过滤用户ID
     * @return 过滤快照
     */
    public ProductExecutionList selectProductExecutionListFilter(ProductExecutionList productExecutionList);

    /**
     * 保存或更新当前用户的过滤快照
     * @param productExecutionList 过滤文件及统计信息
     * @return 结果
     */
    public int saveProductExecutionListFilter(ProductExecutionList productExecutionList);

    /**
     * 查询清单的全部过滤文件路径，删除清单时清理
     * @param id 清单主键
     * @return 文件路径集合
     */
    public List<String> selectProductExecutionListFilterPaths(Long id);

    /**
     * 删除清单的全部用户过滤快照
     * @param id 清单主键
     * @return 结果
     */
    public int deleteProductExecutionListFilters(Long id);
}
