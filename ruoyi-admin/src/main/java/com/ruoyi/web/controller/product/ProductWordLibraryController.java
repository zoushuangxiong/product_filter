package com.ruoyi.web.controller.product;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.ProductWordLibrary;
import com.ruoyi.system.service.product.ProductWordLibraryService;

/** 过滤词库管理；检测权限可读取自己的词库，修改操作单独授权。 */
@RestController
@RequestMapping("/product/word-library")
public class ProductWordLibraryController extends BaseController
{
    private final ProductWordLibraryService service;
    public ProductWordLibraryController(ProductWordLibraryService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('product:wordLibrary:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) String name)
    {
        startPage();
        return getDataTable(service.list(getUserId(), name));
    }

    @PreAuthorize("@ss.hasPermi('product:scan:use')")
    @GetMapping("/options")
    public AjaxResult options() { return AjaxResult.success(service.list(getUserId(), null)); }

    @PreAuthorize("@ss.hasAnyPermi('product:wordLibrary:list,product:scan:use')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id) { return AjaxResult.success(service.get(getUserId(), id)); }

    @PreAuthorize("@ss.hasPermi('product:wordLibrary:add')")
    @Log(title = "过滤词库", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ProductWordLibrary library)
    { return toAjax(service.save(getUserId(), getUsername(), library, true)); }

    @PreAuthorize("@ss.hasPermi('product:wordLibrary:edit')")
    @Log(title = "过滤词库", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ProductWordLibrary library)
    { return toAjax(service.save(getUserId(), getUsername(), library, false)); }

    @PreAuthorize("@ss.hasPermi('product:wordLibrary:remove')")
    @Log(title = "过滤词库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable Long id) { return toAjax(service.delete(getUserId(), id)); }
}
