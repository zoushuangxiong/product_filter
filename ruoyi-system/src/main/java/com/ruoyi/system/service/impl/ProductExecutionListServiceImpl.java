package com.ruoyi.system.service.impl;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.ProductExecutionList;
import com.ruoyi.system.mapper.ProductExecutionListMapper;
import com.ruoyi.system.service.IProductExecutionListService;
import com.ruoyi.system.service.product.ExecutionListFiles;
import com.ruoyi.system.service.product.ExecutionListSource;
import com.ruoyi.system.service.product.ProductScanService;
import com.ruoyi.system.service.product.ScanModels;

/**
 * 执行清单Service业务层处理：文件持久化、备注维护和按当前账号过滤已检测商品
 *
 * @author ruoyi
 * @date 2026-09-23
 */
@Service
public class ProductExecutionListServiceImpl implements IProductExecutionListService
{
    @Autowired
    private ProductExecutionListMapper productExecutionListMapper;
    @Autowired
    private ExecutionListFiles executionListFiles;
    @Autowired
    private ProductScanService productScanService;

    /**
     * 查询执行清单及当前用户的过滤统计
     * @param id 执行清单主键
     * @return 执行清单
     */
    @Override
    public ProductExecutionList selectProductExecutionListById(Long id)
    {
        ProductExecutionList result = productExecutionListMapper.selectProductExecutionListById(id);
        if (result != null) attachFilter(result, currentFilter(id));
        return result;
    }

    /**
     * 查询执行清单列表，不读取表格文件
     * @param productExecutionList 执行清单查询条件
     * @return 执行清单集合
     */
    @Override
    public List<ProductExecutionList> selectProductExecutionListList(ProductExecutionList productExecutionList)
    {
        // 清单全局共享，过滤结果按当前账号关联，不能使用客户端提供的用户ID。
        productExecutionList.getParams().put("ownerId", SecurityUtils.getUserId());
        return productExecutionListMapper.selectProductExecutionListList(productExecutionList);
    }

    /**
     * 新增执行清单，保存原始文件并统计数量，数据库不保存表格正文
     * @param productExecutionList 含上传文件、平台和备注的清单
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertProductExecutionList(ProductExecutionList productExecutionList)
    {
        validate(productExecutionList);
        String extension = productExecutionList.getFileName().toLowerCase(Locale.ROOT).endsWith(".xlsx") ? ".xlsx" : ".csv";
        String path = executionListFiles.save(productExecutionList.getFile(), extension);
        try
        {
            var source = ExecutionListSource.read(executionListFiles.resolve(path), productExecutionList.getPlatform());
            productExecutionList.setId(null);
            productExecutionList.setFilePath(path);
            productExecutionList.setRecordCount(source.recordCount());
            productExecutionList.setProductCount(source.productCount());
            productExecutionList.setCreateTime(DateUtils.getNowDate());
            return productExecutionListMapper.insertProductExecutionList(productExecutionList);
        }
        catch (Exception e)
        {
            executionListFiles.remove(path);
            if (e instanceof DuplicateKeyException) throw new ServiceException("已上传同名文件，请勿重复上传");
            throw e;
        }
    }

    /**
     * 仅修改备注，业务字段不参与更新
     * @param productExecutionList 主键和备注
     * @return 结果
     */
    @Override
    public int updateProductExecutionList(ProductExecutionList productExecutionList)
    {
        if (productExecutionListMapper.selectProductExecutionListById(productExecutionList.getId()) == null)
            throw new ServiceException("执行清单不存在或已删除");
        validateRemark(productExecutionList.getRemark());
        ProductExecutionList update = new ProductExecutionList();
        update.setId(productExecutionList.getId());
        update.setRemark(productExecutionList.getRemark() == null ? "" : productExecutionList.getRemark());
        update.setUpdateBy(productExecutionList.getUpdateBy());
        update.setUpdateTime(DateUtils.getNowDate());
        return productExecutionListMapper.updateProductExecutionList(update);
    }

    /**
     * 批量删除清单，提交后清理原件及所有账号的过滤文件
     * @param ids 清单主键集合
     * @return 删除数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteProductExecutionListByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0) throw new ServiceException("请选择需要删除的执行清单");
        int count = 0;
        // 多清单按固定顺序加锁，避免同时批量删除时死锁。
        for (Long id : Arrays.stream(ids).distinct().sorted().toList())
        {
            ProductExecutionList item = productExecutionListMapper.selectProductExecutionListForUpdate(id);
            if (item == null) continue;
            List<String> paths = productExecutionListMapper.selectProductExecutionListFilterPaths(id);
            productExecutionListMapper.deleteProductExecutionListFilters(id);
            count += productExecutionListMapper.deleteProductExecutionListById(id);
            executionListFiles.removeAfterCommit(item.getFilePath());
            paths.forEach(executionListFiles::removeAfterCommit);
        }
        return count;
    }

    /**
     * 删除单个清单及关联文件
     * @param id 清单主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteProductExecutionListById(Long id)
    {
        return deleteProductExecutionListByIds(new Long[] {id});
    }

    /**
     * 按当前账号已成功获取的商品ID过滤，始终从原件重新计算
     * @param id 清单主键
     * @return 原始统计及最新过滤统计
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductExecutionList filterProductExecutionList(Long id)
    {
        ProductExecutionList item = locked(id);
        var source = ExecutionListSource.read(executionListFiles.resolve(item.getFilePath()), item.getPlatform());
        var filtered = ExecutionListSource.filter(source, productScanService.checkedIds(SecurityUtils.getUserId(), item.getPlatform()));
        ProductExecutionList previous = currentFilter(id);
        ProductExecutionList snapshot = new ProductExecutionList();
        snapshot.setId(id);
        snapshot.setFilterOwnerId(SecurityUtils.getUserId());
        snapshot.setFilteredFilePath(executionListFiles.saveCsv(filtered.csv()));
        snapshot.setFilteredRecordCount(filtered.recordCount());
        snapshot.setFilteredProductCount(filtered.productCount());
        snapshot.setFilteredTime(DateUtils.getNowDate());
        snapshot.setFilterVersion(UUID.randomUUID().toString());
        try { productExecutionListMapper.saveProductExecutionListFilter(snapshot); }
        catch (RuntimeException e) { executionListFiles.remove(snapshot.getFilteredFilePath()); throw e; }
        if (previous != null) executionListFiles.removeAfterCommit(previous.getFilteredFilePath());
        attachFilter(item, snapshot);
        return item;
    }

    /**
     * 从清单读取本次任务的固定快照，执行全量或指定版本的过滤结果
     * @param request 清单ID、执行范围及检测规则
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareExecution(ScanModels.Request request)
    {
        if (request == null || request.executionListId == null) throw new ServiceException("请选择执行清单");
        ProductExecutionList item = locked(request.executionListId);
        String path = item.getFilePath();
        if ("FILTERED".equals(request.executionMode))
        {
            ProductExecutionList snapshot = currentFilter(item.getId());
            if (snapshot == null) throw new ServiceException("请先在执行清单中进行过滤已检测");
            if (!snapshot.getFilterVersion().equals(request.filterVersion)) throw new ServiceException("过滤结果已更新，请刷新执行清单后重新选择");
            if (snapshot.getFilteredRecordCount() == 0) throw new ServiceException("过滤后没有待检测商品，请选择其他清单或完全执行");
            path = snapshot.getFilteredFilePath();
        }
        else if (!"ALL".equals(request.executionMode)) throw new ServiceException("请选择完全执行或执行过滤后的商品");
        var source = ExecutionListSource.read(executionListFiles.resolve(path), item.getPlatform());
        // 后端决定平台、商品及原始列内容，不信任客户端提交的同名字段。
        request.platform = item.getPlatform();
        request.items = source.items();
        request.sourceCsv = source.csv();
    }

    /** 校验上传文件名、平台、备注及全局文件名唯一性。 */
    private void validate(ProductExecutionList item)
    {
        validateRemark(item.getRemark());
        if (!Set.of("taobao", "1688").contains(item.getPlatform() == null ? "" : item.getPlatform()))
            throw new ServiceException("请选择商品平台");
        String name = item.getFile() == null ? null : item.getFile().getOriginalFilename();
        if (name == null || name.isBlank() || name.trim().length() > 255 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl)) throw new ServiceException("请上传有效文件，文件名不能超过255个字符");
        name = Normalizer.normalize(name.trim(), Normalizer.Form.NFC);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) throw new ServiceException("只支持 CSV 或 Excel（.xlsx）文件");
        if (productExecutionListMapper.selectProductExecutionListIdByFileName(name) != null)
            throw new ServiceException("已上传同名文件，请勿重复上传");
        item.setFileName(name);
    }

    /** 备注可以清空，最多500个字符。 */
    private static void validateRemark(String remark)
    {
        if (remark != null && remark.length() > 500) throw new ServiceException("备注不能超过500个字符");
    }

    /** 查询并锁定清单；文件操作期间禁止删除或替换过滤结果。 */
    private ProductExecutionList locked(Long id)
    {
        ProductExecutionList item = productExecutionListMapper.selectProductExecutionListForUpdate(id);
        if (item == null) throw new ServiceException("执行清单不存在或已删除");
        return item;
    }

    /** 查询当前账号的过滤结果，账号之间互不覆盖。 */
    private ProductExecutionList currentFilter(Long id)
    {
        ProductExecutionList query = new ProductExecutionList();
        query.setId(id); query.setFilterOwnerId(SecurityUtils.getUserId());
        return productExecutionListMapper.selectProductExecutionListFilter(query);
    }

    /** 将快照统计附加到清单，不改动原始记录数和商品数。 */
    private static void attachFilter(ProductExecutionList item, ProductExecutionList snapshot)
    {
        if (snapshot == null) return;
        item.setFilteredRecordCount(snapshot.getFilteredRecordCount());
        item.setFilteredProductCount(snapshot.getFilteredProductCount());
        item.setFilteredTime(snapshot.getFilteredTime());
        item.setFilterVersion(snapshot.getFilterVersion());
    }
}
