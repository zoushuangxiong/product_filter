package com.ruoyi.web.controller.product;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.ProductExecutionList;
import com.ruoyi.system.service.IProductExecutionListService;

/**
 * 执行清单Controller
 *
 * @author ruoyi
 * @date 2026-09-23
 */
@RestController
@RequestMapping("/product/execution-list")
public class ProductExecutionListController extends BaseController
{
    @Autowired
    private IProductExecutionListService productExecutionListService;

    /**
     * 商品检测页清单下拉选项，复用分页以避免清单过多时加载全部数据
     */
    @PreAuthorize("@ss.hasPermi('product:scan:use')")
    @GetMapping("/options")
    public TableDataInfo options(ProductExecutionList productExecutionList)
    {
        startPage();
        return getDataTable(productExecutionListService.selectProductExecutionListList(productExecutionList));
    }

    /** 检测页按需读取所选清单的最新统计及过滤版本。 */
    @PreAuthorize("@ss.hasPermi('product:scan:use')")
    @GetMapping("/options/{id}")
    public AjaxResult option(@PathVariable("id") Long id)
    {
        return success(productExecutionListService.selectProductExecutionListById(id));
    }

    /**
     * 过滤当前账号已成功获取的商品，生成独立结果文件
     */
    @PreAuthorize("@ss.hasPermi('product:executionList:filter')")
    @Log(title = "执行清单过滤", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/filter")
    public AjaxResult filter(@PathVariable("id") Long id)
    {
        return success(productExecutionListService.filterProductExecutionList(id));
    }

    /**
     * 查询执行清单列表
     */
    @PreAuthorize("@ss.hasPermi('product:executionList:list')")
    @GetMapping("/list")
    public TableDataInfo list(ProductExecutionList productExecutionList)
    {
        startPage();
        List<ProductExecutionList> list = productExecutionListService.selectProductExecutionListList(productExecutionList);
        return getDataTable(list);
    }

    /**
     * 获取执行清单详细信息
     */
    @PreAuthorize("@ss.hasPermi('product:executionList:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(productExecutionListService.selectProductExecutionListById(id));
    }

    /**
     * 新增执行清单；原始表格不写入操作日志
     */
    @PreAuthorize("@ss.hasPermi('product:executionList:add')")
    @Log(title = "执行清单", businessType = BusinessType.INSERT, isSaveRequestData = false)
    @PostMapping
    public AjaxResult add(@ModelAttribute ProductExecutionList productExecutionList)
    {
        productExecutionList.setCreateBy(getUsername());
        return toAjax(productExecutionListService.insertProductExecutionList(productExecutionList));
    }

    /**
     * 修改执行清单备注，其他业务字段不允许更新
     */
    @PreAuthorize("@ss.hasPermi('product:executionList:edit')")
    @Log(title = "执行清单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ProductExecutionList productExecutionList)
    {
        productExecutionList.setUpdateBy(getUsername());
        return toAjax(productExecutionListService.updateProductExecutionList(productExecutionList));
    }

    /**
     * 删除执行清单
     */
    @PreAuthorize("@ss.hasPermi('product:executionList:remove')")
    @Log(title = "执行清单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable("ids") Long[] ids)
    {
        return toAjax(productExecutionListService.deleteProductExecutionListByIds(ids));
    }
}
