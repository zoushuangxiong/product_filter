package com.ruoyi.web.controller.product;

import java.util.List;
import java.util.Map;
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
import com.ruoyi.system.domain.ProductWhitelist;
import com.ruoyi.system.service.IProductWhitelistService;

/**
 * 白名单库Controller
 *
 * @author product-filter
 * @date 2026-09-24
 */
@RestController
@RequestMapping("/product/whitelist")
public class ProductWhitelistController extends BaseController
{
    @Autowired
    private IProductWhitelistService productWhitelistService;

    /**
     * 查询白名单库列表。
     */
    @PreAuthorize("@ss.hasPermi('product:whitelist:list')")
    @GetMapping("/list")
    public TableDataInfo list(ProductWhitelist productWhitelist)
    {
        startPage();
        List<ProductWhitelist> list = productWhitelistService.selectProductWhitelistList(productWhitelist);
        return getDataTable(list);
    }

    /**
     * 获取白名单详细信息。
     */
    @PreAuthorize("@ss.hasAnyPermi('product:whitelist:list,product:whitelist:add,product:whitelist:edit')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(productWhitelistService.selectProductWhitelistById(id));
    }

    /**
     * 新增白名单，重复时返回已有主键供页面确认后追加。
     */
    @PreAuthorize("@ss.hasPermi('product:whitelist:add')")
    @Log(title = "白名单库", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ProductWhitelist productWhitelist)
    {
        int rows = productWhitelistService.insertProductWhitelist(productWhitelist);
        return success(Map.of("duplicate", rows == 0, "id", productWhitelist.getId()));
    }

    /**
     * 追加白名单匹配内容。
     */
    @PreAuthorize("@ss.hasPermi('product:whitelist:add')")
    @Log(title = "白名单库追加", businessType = BusinessType.UPDATE)
    @PostMapping("/append")
    public AjaxResult append(@RequestBody ProductWhitelist productWhitelist)
    {
        return toAjax(productWhitelistService.appendProductWhitelist(productWhitelist));
    }

    /**
     * 修改白名单匹配内容。
     */
    @PreAuthorize("@ss.hasPermi('product:whitelist:edit')")
    @Log(title = "白名单库", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ProductWhitelist productWhitelist)
    {
        return toAjax(productWhitelistService.updateProductWhitelist(productWhitelist));
    }

    /**
     * 启用或禁用白名单。
     */
    @PreAuthorize("@ss.hasPermi('product:whitelist:edit')")
    @Log(title = "白名单库状态", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody ProductWhitelist productWhitelist)
    {
        return toAjax(productWhitelistService.changeProductWhitelistStatus(productWhitelist));
    }

    /**
     * 删除未配置匹配内容的白名单。
     */
    @PreAuthorize("@ss.hasPermi('product:whitelist:remove')")
    @Log(title = "白名单库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(productWhitelistService.deleteProductWhitelistById(id));
    }
}
