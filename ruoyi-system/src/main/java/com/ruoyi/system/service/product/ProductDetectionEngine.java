package com.ruoyi.system.service.product;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.ruoyi.system.service.product.ScanModels.*;

/**
 * 云端本机与远程工作机共用的检测算法，不查询词库、白名单或执行单数据库。
 * prepare 先匹配导入标题，再按需获取商品；execute 逐图 OCR，命中后跳过剩余图片。
 * 共享数据的修改及 changed 回调在调用方提供的锁内执行，下载/OCR 不持有该锁。
 */
public final class ProductDetectionEngine
{
    public record Cached(Ocr ocr, String key, int qrCodes) { }
    private ProductDetectionEngine() { }

    /** 完整检测的准备阶段，参数由调用方提供；fetch 仅在标题未命中且需要商品信息时调用。 */
    public static boolean prepare(Product p, Request rules, List<WhitelistRule> whitelistRules,
            List<String> titles, boolean rechecking, boolean retrying,
            java.util.function.Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> fetch,
            Object lock, Runnable changed, Runnable quotaReached)
    {
        return prepareCompiled(p, ScanRules.prepare(rules.titleWords), new ScanWhitelist(whitelistRules),
                titles, rechecking, retrying, fetch, lock, changed, quotaReached);
    }
    static boolean prepareCompiled(Product p, ScanRules.PreparedWords titleWords, ScanWhitelist whitelist,
            List<String> titles, boolean rechecking, boolean retrying,
            java.util.function.Supplier<com.ruoyi.system.utils.taobao.TaobaoProductInfo> fetch,
            Object lock, Runnable changed, Runnable quotaReached)
    {
            if (rechecking)
            {
                synchronized (lock)
                {
                    // 首次处理该命中商品才清理旧判定，继续时保留已经重新识别的图片。
                    if ("MATCHED".equals(p.verdict))
                    {
                        p.titleHits = new ArrayList<>(); p.pictures.clear(); p.warnings.clear();
                        p.importedTitleChecked = false; p.providerFetched = false;
                        p.title = null; p.error = null; p.incomplete = false;
                        p.review = "NONE"; p.reviewNote = null; p.reviewedAt = null;
                        p.verdict = "REVIEW"; p.state = "PENDING";
                        changed.run();
                    }
                }
            }
            // 必须在预占额度、调用商品接口之前匹配导入标题。
            
            if (!titles.isEmpty())
            {
                synchronized (lock)
                {
                    if (!p.importedTitleChecked)
                    {
                        p.title = titles.get(0);
                        p.titleHits = List.of();
                        for (String title : titles)
                        {
                            List<String> hits = titleWords.match(title, false, true, whitelist);
                            if (!hits.isEmpty()) { p.title = title; p.titleHits = hits; break; }
                        }
                        p.importedTitleChecked = true;
                    }
                    if (!p.titleHits.isEmpty())
                    {
                        p.state = "DONE"; p.verdict = "MATCHED";
                        p.error = null; p.incomplete = false;
                        changed.run();
                        return true;
                    }
                }
            }
            if (retrying || !p.providerFetched || p.title == null)
            {
            synchronized (lock) { p.state = "FETCHING"; changed.run(); }
            // 缓存查询在额度预占之前；只有数据库没有商品信息时才调用第三方并计费。
            var product = fetch.get();
            if (product == null)
            {
                synchronized (lock)
                {
                    quotaReached.run();
                    if (!retrying) { p.state = "PENDING"; p.error = "DAILY_LIMIT_EXCEEDED"; p.incomplete = true; }
                    else p.state = "FAILED";
                    changed.run();
                }
                return false;
            }
            synchronized (lock)
            {
                if (retrying)
                {
                    p.retryCount++; p.error = null; p.incomplete = false;
                    p.verdict = "REVIEW"; p.title = null; p.titleHits = new ArrayList<>(); p.pictures.clear();
                    p.warnings.clear();
                }
            }
            synchronized (lock)
            {
                p.providerFetched = true;
                p.price = product.getPriceText(); p.categoryName = product.getCategoryName();
                p.shopUrl = product.getShopUrl(); p.sales = product.getSales(); p.commentCount = product.getCommentCount();
                // 有导入标题时已完成匹配，接口标题不再参与判定；无标题才在此补检。
                p.title = titles.isEmpty() ? product.getTitle() : titles.get(0);
                p.titleHits = titles.isEmpty() ? titleWords.match(p.title, false, true, whitelist) : List.of();
                p.warnings.add("检测范围为接口实际返回内容；未命中词库不代表平台合规或上游图片完整");
                addPictures(p, "MAIN", product.getMainImages()); addPictures(p, "DETAIL", product.getDetailImages());
                p.state = "SCANNING"; changed.run();
            }
            }
        return true;
    }

    private static void addPictures(Product p, String kind, List<String> urls) {
        int index = 0;
        for (String url : urls) {
            if (p.pictures.size() >= 200) {
                p.incomplete = true; p.warnings.add("超过单商品 200 张处理上限，有图片未检测"); break;
            }
            Picture pic = new Picture(); pic.kind = kind; pic.index = ++index; pic.url = url; p.pictures.add(pic);
        }
    }

    public static void execute(Product p, Request rules, List<WhitelistRule> whitelist,
            Map<String, Cached> cache, Path assets, ScanGateway gateway,
            Object lock, BooleanSupplier stopped, Runnable changed)
    {
        Request activeRules = rules;
        double threshold = rules.confidenceThreshold;
        ScanRules.PreparedWords imageWords = ScanRules.prepare(rules.imageWords);
        ScanWhitelist allowed = new ScanWhitelist(whitelist);
        executePrepared(p, activeRules, threshold, imageWords, allowed, cache, assets, gateway, lock, stopped, changed);
    }

    static void executePrepared(Product p, Request activeRules, double threshold,
            ScanRules.PreparedWords imageWords, ScanWhitelist whitelist, Map<String, Cached> cache,
            Path assets, ScanGateway gateway, Object lock, BooleanSupplier stopped, Runnable changed)
    {
            synchronized (lock)
            {
                p.error = null;
                p.incomplete = p.pictures.stream().anyMatch(pic -> "FAILED".equals(pic.state)
                        || (pic.ocr != null && pic.ocr.lines.stream().anyMatch(line -> line.score < threshold)));
                p.state = "SCANNING";
            }
            // 标题或中断前的图片已经命中时，直接保留不通过结果，不再下载剩余图片。
            if (!p.titleHits.isEmpty() || p.pictures.stream().anyMatch(pic -> !pic.hits.isEmpty() || pic.qrCodes > 0))
            {
                synchronized (lock)
                {
                    p.state = "DONE";
                    p.verdict = "MATCHED";
                    changed.run();
                }
                return;
            }
            for (Picture pic : p.pictures)
            {
                if (stopped.getAsBoolean()) break;
                if (Set.of("DONE", "FAILED", "SKIPPED").contains(pic.state)) continue;
                synchronized (lock) { pic.state = "SCANNING"; changed.run(); }
                try
                {
                    Cached cached = cache.computeIfAbsent(pic.url, url -> {
                        try {
                        ScanGateway.Inspection inspection = gateway.inspect(pic.url);
                        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inspection.preview()));
                        Path dest = assets.resolve(key + ".jpg");
                        if (!Files.exists(dest))
                        {
                            Path tmp = Files.createTempFile(assets, "preview-", ".tmp");
                            try { Files.write(tmp, inspection.preview()); Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); }
                            finally { Files.deleteIfExists(tmp); }
                        }
                        return new Cached(inspection.ocr(), key, inspection.qrCodes());
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    });
                    Set<String> hits = new LinkedHashSet<>();
                    Ocr evidence = new Ocr();
                    evidence.width = cached.ocr.width; evidence.height = cached.ocr.height; evidence.engine = cached.ocr.engine;
                    // 不修改复用的原始OCR缓存，保存本行实际命中，避免将已放行的其他行再次标红。
                    for (Line original : cached.ocr.lines)
                    {
                        Line line = new Line(); line.text = original.text; line.score = original.score; line.box = original.box;
                        line.hits = imageWords.match(line.text, activeRules.detectPhones, false, whitelist);
                        hits.addAll(line.hits); evidence.lines.add(line);
                    }
                    synchronized (lock)
                    {
                        pic.ocr = evidence; pic.previewKey = cached.key; pic.hits = List.copyOf(hits); pic.qrCodes = activeRules.detectQrCodes ? cached.qrCodes : 0; pic.state = "DONE";
                        if (pic.ocr.lines.stream().anyMatch(line -> line.score < threshold))
                        { p.incomplete = true; p.warnings.add(pic.kind + pic.index + " 有低置信度文字，请人工核查"); }
                        changed.run();
                    }
                    // 当前图片已经命中时，商品确定不通过，跳过同一商品剩余图片。
                    if (!hits.isEmpty() || (activeRules.detectQrCodes && cached.qrCodes > 0))
                    {
                        synchronized (lock)
                        {
                            boolean current = false;
                            for (Picture remaining : p.pictures)
                            {
                                if (remaining == pic) { current = true; continue; }
                                if (current && "PENDING".equals(remaining.state)) remaining.state = "SKIPPED";
                            }
                            changed.run();
                        }
                        break;
                    }
                }
                catch (Exception e)
                {
                    synchronized (lock) { pic.state = "FAILED"; pic.error = "图片读取或识别失败"; p.incomplete = true; changed.run(); }
                }
            }
            synchronized (lock)
            {
                boolean unfinished = p.pictures.stream().anyMatch(pic -> !"DONE".equals(pic.state) && !"SKIPPED".equals(pic.state));
                p.incomplete |= unfinished;
                p.state = stopped.getAsBoolean() ? "CANCELLED" : "DONE";
                p.verdict = !p.titleHits.isEmpty() || p.pictures.stream().anyMatch(pic -> !pic.hits.isEmpty() || pic.qrCodes > 0) ? "MATCHED"
                        : p.incomplete ? "REVIEW" : "CLEAR";
                changed.run();
            }
    }
}
