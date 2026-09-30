package com.ruoyi.system.service.product;

import java.util.List;
import static com.ruoyi.system.service.product.ScanModels.*;

/**
 * 云端与工作机共用的 v4 协议。仅传检测参数和结果，不传图片字节或第三方 API 密钥。
 * 字段同时用于 JSON 通信和本地 outbox 恢复；调整字段时需兼容未确认的历史结果。
 */
public final class ScanWorkerProtocol {
    private ScanWorkerProtocol() { }
    /** 一次商品领取的输入；租约用于拒绝超时重派后旧机器提交的结果。 */
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
    /** 每个操作携带机器/进程身份；只有进度和完成操作携带 product。 */
    public static class Message {
        public int version = 4;
        public String workerId, taskId, lease, session;
        public int capacity = 1;
        public Product product;
    }
    public static class Reply {
        public boolean accepted, cancel;
        public long expiresAt;
    }
    /** 商品缓存/接口代理的结果；同一租约重试复用此结果，避免重复查询和扣量。 */
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
    /** 回传前原子写入磁盘；云端确认保存后才能删除。 */
    public static class Saved {
        public Input input;
        public Product product;
    }
}
