package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.ProductExecutionList;
import com.ruoyi.system.service.product.ScanModels;

/**
 * 执行清单Service接口
 *
 * @author ruoyi
 * @date 2026-09-23
 */
public interface IProductExecutionListService
{
    /**
     * 按当前账号的历史获取成功记录过滤清单
     * @param id 清单主键
     * @return 包含过滤前后数量的清单
     */
    public ProductExecutionList filterProductExecutionList(Long id);

    /**
     * 从指定清单和执行范围读取任务快照
     * @param request 清单ID、过滤版本及检测规则
     */
    public void prepareExecution(ScanModels.Request request);

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
}
