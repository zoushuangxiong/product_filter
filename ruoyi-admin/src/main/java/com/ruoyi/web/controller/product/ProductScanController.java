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
import com.ruoyi.system.service.IProductExecutionListService;

/** 商品检测接口；统一校验功能权限，任务归属由服务层按当前用户检查。 */
@RestController
@RequestMapping("/product/scan")
@PreAuthorize("@ss.hasPermi('product:scan:use')")
public class ProductScanController
{
    private final ProductScanService service;
    private final IProductExecutionListService executionListService;
    public ProductScanController(ProductScanService service, IProductExecutionListService executionListService)
    {
        this.service = service;
        this.executionListService = executionListService;
    }

    /** 从执行清单指定的范围读取商品，创建异步检测任务。 */
    @PostMapping("/tasks")
    public AjaxResult create(@RequestBody ScanModels.Request request)
    {
        ScanModels.Job existing = service.executionTask(SecurityUtils.getUserId(), request.executionListId);
        if (existing != null) return AjaxResult.success(existing);
        executionListService.prepareExecution(request);
        return AjaxResult.success(service.create(SecurityUtils.getUserId(), request));
    }

    @GetMapping("/tasks")
    public AjaxResult list() { return AjaxResult.success(service.list(SecurityUtils.getUserId())); }

    @GetMapping("/tasks/usage")
    public AjaxResult usage() { return AjaxResult.success(service.usage()); }

    /** 选择执行清单时读取已有检测，不创建新任务。 */
    @GetMapping("/execution-lists/{listId}/task")
    public AjaxResult executionTask(@PathVariable("listId") Long listId)
    { return AjaxResult.success(service.executionTask(SecurityUtils.getUserId(), listId)); }

    /** 停止或中断后继续原任务，保留已完成结果。 */
    @PostMapping("/tasks/{id}/resume")
    public AjaxResult resume(@PathVariable("id") String id)
    { return AjaxResult.success(service.resume(SecurityUtils.getUserId(), id)); }

    @GetMapping("/tasks/{id}")
    public AjaxResult get(@PathVariable("id") String id,
            @RequestParam(name = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
            @RequestParam(name = "filter", defaultValue = "ALL") String filter)
    {
        return AjaxResult.success(service.page(SecurityUtils.getUserId(), id, pageNum, pageSize, filter));
    }

    @PostMapping("/tasks/{id}/cancel")
    public AjaxResult cancel(@PathVariable("id") String id) { return AjaxResult.success(service.cancel(SecurityUtils.getUserId(), id)); }

    /** 人工重新尝试获取单个商品信息，不限制次数。 */
    @PostMapping("/tasks/{id}/products/{itemId}/retry")
    public AjaxResult retry(@PathVariable("id") String id, @PathVariable("itemId") String itemId)
    { return AjaxResult.success(service.retryProduct(SecurityUtils.getUserId(), id, itemId)); }

    /** 一键重试当前任务中获取异常商品。 */
    @PostMapping("/tasks/{id}/retry-failed")
    public AjaxResult retryFailed(@PathVariable("id") String id)
    {
        return AjaxResult.success(service.retryFailedProducts(SecurityUtils.getUserId(), id));
    }

    /** 按最新白名单重新检测整个任务中的命中商品。 */
    @PostMapping("/tasks/{id}/recheck-matched")
    public AjaxResult recheckMatched(@PathVariable("id") String id)
    { return AjaxResult.success(service.recheckMatched(SecurityUtils.getUserId(), id)); }

    /** 修改已完成任务的低置信度阈值并重新计算结果，不重新调用商品接口。 */
    @PostMapping("/tasks/{id}/confidence-threshold")
    public AjaxResult confidenceThreshold(@PathVariable("id") String id, @RequestParam("value") double value)
    { return AjaxResult.success(service.updateThreshold(SecurityUtils.getUserId(), id, value)); }

    @PostMapping("/tasks/{id}/products/{itemId}/review")
    public AjaxResult review(@PathVariable("id") String id, @PathVariable("itemId") String itemId, @RequestBody ScanModels.Review review)
    { return AjaxResult.success(service.review(SecurityUtils.getUserId(), id, itemId, review)); }

    /** 按需读取单张图片的文字、置信度和标记坐标。 */
    @GetMapping("/tasks/{id}/products/{itemId}/images/{index}/detail")
    public AjaxResult evidence(@PathVariable("id") String id, @PathVariable("itemId") String itemId, @PathVariable("index") int index)
    {
        return AjaxResult.success(service.evidence(SecurityUtils.getUserId(), id, itemId, index));
    }

    /** 返回识别时保存的图片预览；index 是图片列表下标，从 0 开始。 */
    @GetMapping("/tasks/{id}/products/{itemId}/images/{index}")
    public ResponseEntity<byte[]> image(@PathVariable("id") String id, @PathVariable("itemId") String itemId, @PathVariable("index") int index)
    {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.IMAGE_JPEG).body(service.image(SecurityUtils.getUserId(), id, itemId, index));
    }

    /** eligibleOnly 为 true 时导出固定模板的通过项，否则导出完整检测报告。 */
    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable("id") String id, @RequestParam(name = "eligibleOnly", defaultValue = "false") boolean eligibleOnly)
    {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=product-scan.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.export(SecurityUtils.getUserId(), id, eligibleOnly));
    }
}
