package com.ruoyi.system.service.product;

import java.util.ArrayList;
import java.util.List;

/** 任务快照同时用于持久化和 API 响应，不含任何接口凭据。 */
public final class ScanModels
{
    private ScanModels() { }

    /** 导入内容与检测规则；sourceCsv 保留原始列值，供通过项按模板导出。 */
    public static class Request
    {
        /** 本次执行的清单ID。 */
        public Long executionListId;
        /** ALL：完全执行；FILTERED：执行已保存的过滤结果。 */
        public String executionMode;
        /** 过滤结果版本，防止页面旧统计对应到更新后的文件。 */
        public String filterVersion;
        public List<String> items = new ArrayList<>();
        public String platform;
        public String sourceCsv;
        public String titleWords = "";
        public String imageWords = "";
        public boolean detectPhones;
        /** 是否检测图片中的二维码。 */
        public boolean detectQrCodes;
        /** 低于该置信度的 OCR 结果需要复核，支持 0.6、0.5、0.4。 */
        public double confidenceThreshold = 0.6;
    }

    /** 历史下拉列表仅使用摘要，不携带 CSV、图片识别结果及坐标。 */
    public record TaskSummary(String id, String createdAt, String state, String platform, int productCount,
            String startedAt, String completedAt, long durationMillis) { }

    /** 执行时保存的白名单规则，与数据库后续修改隔离。 */
    public static class WhitelistRule
    {
        public Long id;
        public String filterWord;
        public String matchType;
        public String matchContent;
    }

    /** 异步任务快照，包含用户归属、进度和商品结果。 */
    public static class Job
    {
        public String id;
        public long ownerId;
        public String createdAt;
        public String updatedAt;
        /** 实际开始处理时间，不包含排队时间。 */
        public String startedAt;
        /** 实际处理结束时间。 */
        public String completedAt;
        /** 实际处理耗时，单位毫秒。 */
        public long durationMillis;
        /** 本轮继续执行的开始时间，用于累计耗时，不计暂停时间。 */
        public String segmentStartedAt;
        /** 是否仍有未执行完的商品（不含获取异常）。 */
        public boolean resumable;
        public String state = "QUEUED";
        public boolean cancelRequested;
        public String error;
        public Request rules;
        /** null表示旧任务尚未固定白名单；空集合表示任务创建时没有启用规则。 */
        public List<WhitelistRule> whitelistRules;
        public String ruleVersion = "nfkc-casefold-substring-v1";
        public List<Product> products = new ArrayList<>();
        /** 单商品人工重试时使用，任务完成后清空。 */
        public String retryOnlyItemId;
        /** 本轮人工重试的商品ID；只执行选中商品，不重新扫描其他结果。 */
        public List<String> retryItemIds = new ArrayList<>();
        /** 本轮命中项重检的剩余商品，停止或重启后保留供继续执行。 */
        public List<String> recheckItemIds = new ArrayList<>();
        /** 本轮重检时最新的白名单快照，不覆盖其他商品原任务的规则。 */
        public List<WhitelistRule> recheckWhitelist;
    }

    /** 分页查询响应；统计覆盖整个任务，products 只包含当前页。 */
    public static class TaskPage
    {
        public Job job;
        public int pageNum, pageSize, total;
        public int productCount, matched, review, incomplete, providerError, unavailable, passed;
        /** 整个任务中可人工重试的获取异常商品数，不受历史重试次数限制。 */
        public int retryable;
        public int processed, failed, totalImages, processedImages;
        public int currentIndex;
        public String currentItemId, currentState;
        public int currentImages, currentProcessedImages;
    }

    /** 单商品结果；执行状态、规则判定和人工复核分别保存。 */
    public static class Product
    {
        public String platform = "taobao";
        public String itemId;
        public String title;
        public String price, categoryName, shopUrl, sales, commentCount;
        public String state = "PENDING";
        public String verdict = "REVIEW";
        public String error;
        /** 商品信息获取异常的人工重试次数。 */
        public int retryCount;
        /** 第三方接口已成功返回商品信息，用于后续导入过滤。 */
        public boolean providerFetched;
        /** 已用导入标题匹配；该标记不代表调用过商品接口。 */
        public boolean importedTitleChecked;
        public String review = "NONE";
        public String reviewNote;
        public String reviewedAt;
        // 识别失败、低置信度或检测中断等情况；为 true 时不能导出通过项。
        public boolean incomplete;
        public List<String> warnings = new ArrayList<>();
        public List<String> titleHits = new ArrayList<>();
        public List<Picture> pictures = new ArrayList<>();
    }

    /** 单张图片；index 为同类图片的展示序号，从 1 开始，与接口列表下标不同。 */
    public static class Picture
    {
        public String kind;
        public int index;
        public String url;
        public String state = "PENDING";
        public String error;
        /** 列表摘要使用，完整识别文字在图片详情接口返回。 */
        public boolean lowConfidence;
        public Ocr ocr;
        public String previewKey;
        public List<String> hits = new ArrayList<>();
        public int qrCodes;
    }

    /** OCR 结果：width/height 是统一方向后的原图尺寸，不是缩放预览尺寸。 */
    public static class Ocr
    {
        public List<Line> lines = new ArrayList<>();
        public int width;
        public int height;
        public String engine;
    }

    public static class Line
    {
        public String text;
        /** 本行经过白名单后的实际命中；null兼容旧任务，空集合表示本行未命中。 */
        public List<String> hits;
        // 识别置信度为 0～1；低于任务指定阈值的已返回文字需要人工核查。
        public double score;
        // 原图上的四个角点，每个点为 [x, y]；前端按原图比例绘制标框。
        public List<List<Double>> box;
    }

    public static class Review
    {
        public String decision;
        public String note;
    }
}
