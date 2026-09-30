package com.ruoyi.system.service.product;

import java.util.List;
import static com.ruoyi.system.service.product.ScanModels.*;

/** 工作机专用参数入口；不包含执行单、词库的数据库依赖或第三方 API 密钥。 */
public final class ScanWorkerProtocol {
    private ScanWorkerProtocol() { }
    public static class Input {
        public int version = 4;
        public String taskId, lease, session;
        public long expiresAt;
        public Product product;
        public Request rules;
        public List<WhitelistRule> whitelist;
        public List<String> importedTitles = List.of();
        public boolean rechecking, retrying;
    }
    public static class Message {
        public int version = 4;
        public String workerId, taskId, lease, session;
        public int capacity = 1;
        public Product product;
        public String artifactKey;
        public byte[] artifact;
    }
    public static class Reply {
        public boolean accepted, cancel;
        public long expiresAt;
    }
    public static class FetchReply {
        public boolean accepted = true, quotaReached;
        public String error, title, priceText, categoryName, shopUrl, sales, commentCount;
        public List<String> mainImages = List.of(), detailImages = List.of();
        public com.ruoyi.system.utils.taobao.TaobaoProductInfo info() {
            return new com.ruoyi.system.utils.taobao.TaobaoProductInfo(title, mainImages, priceText,
                    detailImages, categoryName, shopUrl, sales, commentCount);
        }
        public static FetchReply from(com.ruoyi.system.utils.taobao.TaobaoProductInfo p) {
            FetchReply r = new FetchReply();
            if (p == null) { r.quotaReached = true; return r; }
            r.title=p.getTitle();r.mainImages=p.getMainImages();r.detailImages=p.getDetailImages();
            r.priceText=p.getPriceText();r.categoryName=p.getCategoryName();r.shopUrl=p.getShopUrl();
            r.sales=p.getSales();r.commentCount=p.getCommentCount();return r;
        }
    }
    public record WorkerStatus(String workerId, int capacity, int active, long lastSeen, boolean online) { }
    public static class Saved {
        public Input input;
        public Product product;
    }
}
