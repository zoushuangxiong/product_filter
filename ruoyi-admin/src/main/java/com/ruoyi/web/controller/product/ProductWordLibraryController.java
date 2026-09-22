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
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.ProductWordLibrary;
import com.ruoyi.system.service.IProductWordLibraryService;

/**
 * 过滤词库Controller
 *
 * @author product-filter
 * @date 2026-09-22
 */
@RestController
@RequestMapping("/product/word-library")
public class ProductWordLibraryController extends BaseController
{
    @Autowired
    private IProductWordLibraryService productWordLibraryService;

    /**
     * 查询当前登录用户的过滤词库列表
     */
    @PreAuthorize("@ss.hasPermi('product:wordLibrary:list')")
    @GetMapping("/list")
    public TableDataInfo list(ProductWordLibrary productWordLibrary)
    {
        startPage();
        List<ProductWordLibrary> list = productWordLibraryService.selectProductWordLibraryList(productWordLibrary);
        return getDataTable(list);
    }

    /**
     * 查询商品检测页使用的词库选项
     */
    @PreAuthorize("@ss.hasPermi('product:scan:use')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(productWordLibraryService.selectProductWordLibraryList(new ProductWordLibrary()));
    }

    /**
     * 获取过滤词库详细信息
     */
    @PreAuthorize("@ss.hasAnyPermi('product:wordLibrary:list,product:scan:use')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(productWordLibraryService.selectProductWordLibraryById(getUserId(), id));
    }

    /**
     * 新增过滤词库
     */
    @PreAuthorize("@ss.hasPermi('product:wordLibrary:add')")
    @Log(title = "过滤词库", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ProductWordLibrary productWordLibrary)
    {
        return toAjax(productWordLibraryService.insertProductWordLibrary(getUserId(), getUsername(), productWordLibrary));
    }

    /**
     * 修改过滤词库
     */
    @PreAuthorize("@ss.hasPermi('product:wordLibrary:edit')")
    @Log(title = "过滤词库", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ProductWordLibrary productWordLibrary)
    {
        return toAjax(productWordLibraryService.updateProductWordLibrary(getUserId(), getUsername(), productWordLibrary));
    }

    /**
     * 删除过滤词库
     */
    @PreAuthorize("@ss.hasPermi('product:wordLibrary:remove')")
    @Log(title = "过滤词库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id)
    {
        return toAjax(productWordLibraryService.deleteProductWordLibraryById(getUserId(), id));
    }
}
