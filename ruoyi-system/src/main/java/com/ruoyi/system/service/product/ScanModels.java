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
    public record TaskSummary(String id, String createdAt, String state, String platform, int productCount) { }

    /** 异步任务快照，包含用户归属、进度和商品结果。 */
    public static class Job
    {
        public String id;
        public long ownerId;
        public String createdAt;
        public String updatedAt;
        public String state = "QUEUED";
        public boolean cancelRequested;
        public String error;
        public Request rules;
        public String ruleVersion = "nfkc-casefold-substring-v1";
        public List<Product> products = new ArrayList<>();
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
        /** 第三方接口已成功返回商品信息，用于后续导入过滤。 */
        public boolean providerFetched;
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
