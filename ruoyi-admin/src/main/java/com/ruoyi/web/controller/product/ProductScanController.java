package com.ruoyi.web.controller.product;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.product.ProductScanService;
import com.ruoyi.system.service.product.ScanModels;

/** 商品检测接口；统一校验功能权限，任务归属由服务层按当前用户检查。 */
@RestController
@RequestMapping("/product/scan")
@PreAuthorize("@ss.hasPermi('product:scan:use')")
public class ProductScanController
{
    private final ProductScanService service;
    public ProductScanController(ProductScanService service) { this.service = service; }

    /** 从导入的 CSV 创建异步检测任务。 */
    @PostMapping("/tasks")
    public AjaxResult create(@RequestBody ScanModels.Request request)
    {
        if (request == null || request.sourceCsv == null || request.sourceCsv.isBlank())
            return AjaxResult.error("请先导入 CSV 文件，仅支持 CSV 导入");
        return AjaxResult.success(service.create(SecurityUtils.getUserId(), request));
    }

    @GetMapping("/tasks")
    public AjaxResult list() { return AjaxResult.success(service.list(SecurityUtils.getUserId())); }

    @GetMapping("/tasks/usage")
    public AjaxResult usage() { return AjaxResult.success(service.usage()); }

    @PreAuthorize("@ss.hasPermi('product:scan:use')")
    @GetMapping("/tasks/checked-ids")
    public AjaxResult checkedIds(@RequestParam String platform)
    { return AjaxResult.success(service.checkedIds(SecurityUtils.getUserId(), platform)); }

    @GetMapping("/tasks/{id}")
    public AjaxResult get(@PathVariable String id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "ALL") String filter)
    {
        return AjaxResult.success(service.page(SecurityUtils.getUserId(), id, pageNum, pageSize, filter));
    }

    @PostMapping("/tasks/{id}/cancel")
    public AjaxResult cancel(@PathVariable String id) { return AjaxResult.success(service.cancel(SecurityUtils.getUserId(), id)); }

    /** 人工重新尝试获取单个商品信息，最多一次。 */
    @PostMapping("/tasks/{id}/products/{itemId}/retry")
    public AjaxResult retry(@PathVariable String id, @PathVariable String itemId)
    { return AjaxResult.success(service.retryProduct(SecurityUtils.getUserId(), id, itemId)); }

    /** 修改已完成任务的低置信度阈值并重新计算结果，不重新调用商品接口。 */
    @PostMapping("/tasks/{id}/confidence-threshold")
    public AjaxResult confidenceThreshold(@PathVariable String id, @RequestParam double value)
    { return AjaxResult.success(service.updateThreshold(SecurityUtils.getUserId(), id, value)); }

    @PostMapping("/tasks/{id}/products/{itemId}/review")
    public AjaxResult review(@PathVariable String id, @PathVariable String itemId, @RequestBody ScanModels.Review review)
    { return AjaxResult.success(service.review(SecurityUtils.getUserId(), id, itemId, review)); }

    /** 返回识别时保存的图片预览；index 是图片列表下标，从 0 开始。 */
    @GetMapping("/tasks/{id}/products/{itemId}/images/{index}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String itemId, @PathVariable int index)
    {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.IMAGE_JPEG).body(service.image(SecurityUtils.getUserId(), id, itemId, index));
    }

    /** eligibleOnly 为 true 时导出固定模板的通过项，否则导出完整检测报告。 */
    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "false") boolean eligibleOnly)
    {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=product-scan.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.export(SecurityUtils.getUserId(), id, eligibleOnly));
    }
}
