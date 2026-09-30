package com.ruoyi.system.service.product;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.utils.taobao.TaobaoFetchException;
import com.ruoyi.system.utils.taobao.TaobaoProductInfo;
import static com.ruoyi.system.service.product.ScanModels.*;
import com.ruoyi.system.service.product.ProductDetectionEngine.Cached;

/** 单实例任务队列。快照原子写入私有目录，重启不自动重复付费请求。 */
@Service
public class ProductScanService
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductScanService.class);
    @org.springframework.beans.factory.annotation.Autowired
    private com.ruoyi.system.mapper.ProductWhitelistMapper productWhitelistMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private com.ruoyi.system.service.IProductInfoCacheService productInfoCacheService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.ruoyi.system.service.IProductWordLibraryService productWordLibraryService;

    @org.springframework.beans.factory.annotation.Autowired
    private ScanWorkerBroker remoteWorker;

    /** 首次执行时读取完整启用规则，后续停止、重启和重试沿用快照。 */
    private void captureWhitelist(Job job)
    {
        if (job.whitelistRules != null) return;
        job.whitelistRules = productWhitelistMapper.selectEnabledProductWhitelistList().stream().map(item -> {
            WhitelistRule rule = new WhitelistRule();
            rule.id = item.getId(); rule.filterWord = item.getFilterWord();
            rule.matchType = item.getMatchType(); rule.matchContent = item.getMatchContent();
            return rule;
        }).toList();
    }

    private static final Set<String> PROVIDER_ERRORS = Set.of("MISSING_API_CREDENTIALS", "PROVIDER_ACCESS_DENIED",
            "PROVIDER_ERROR", "RATE_LIMITED", "INVALID_RESPONSE", "TIMEOUT", "NETWORK_ERROR", "HTTP_ERROR", "RESPONSE_TOO_LARGE");

    private static boolean retryable(Product product)
    {
        return "FAILED".equals(product.state) && product.error != null
                && PROVIDER_ERRORS.contains(product.error);
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    // 保存成功后发布不可变摘要；列表不获取任务锁，不等待大快照写盘。
    private final Map<String, TaskSummary> history = new ConcurrentHashMap<>();
    // 发布后不再修改；查询读取一致快照，不竞争检测线程的写盘锁。
    private final Map<String, ReadSnapshot> snapshots = new ConcurrentHashMap<>();
    private record ReadSnapshot(Job overview, List<List<Picture>> evidence) { }
    private final Path storage;
    private final ScanGateway gateway;
    private final Path usageFile;
    private final int dailyLimit;
    private final Object usageLock = new Object();
    private LocalDate usageDate;
    private int usageCount;
    private int reservedCount;
    // 最多同时处理 2 个任务，另排队 20 个；单用户最多有 1 个进行中的任务。
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), runnable -> {
                Thread thread = new Thread(runnable, "product-scan"); thread.setDaemon(true); return thread;
            });

    public ProductScanService(@Value("${product.scan.storage:${user.home}/.product-filter/scans}") String storage,
            @Value("${onebound.key:}") String key, @Value("${onebound.secret:}") String secret, @Value("${product.scan.daily-limit:5000}") int dailyLimit,
            @Value("${product.scan.concurrency:3}") int concurrency,
            @Value("${product.scan.remote.enabled:false}") boolean remoteEnabled,
            @Value("${product.scan.remote.max-inflight:32}") int remoteMaxInflight)
    {
        if (remoteMaxInflight < 1 || remoteMaxInflight > 128) throw new IllegalArgumentException("remote.max-inflight must be 1..128");
        this.productConcurrency = remoteEnabled ? remoteMaxInflight : Math.max(1, Math.min(8, concurrency));
        this.productExecutor = java.util.concurrent.Executors.newFixedThreadPool(productConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "product-worker"); thread.setDaemon(true); return thread;
        });
        this.storage = Path.of(storage).toAbsolutePath();
        this.gateway = new ScanGateway(key, secret);
        this.dailyLimit = dailyLimit > 0 ? dailyLimit : 5000;
        this.usageFile = this.storage.resolve("daily-usage.json");
        try
        {
            Files.createDirectories(this.storage.resolve("assets"));
            loadUsage();
            try { Files.setPosixFilePermissions(this.storage, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { }
            try (var files = Files.list(this.storage))
            {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json") && !p.equals(usageFile)).toList())
                {
                    Job job = JSON.parseObject(Files.readString(file), Job.class);
                    if (job == null || job.id == null || !file.getFileName().toString().equals(job.id + ".json"))
                        throw new IllegalStateException("任务快照格式错误");
                    if (active(job))
                    {
                        if (job.segmentStartedAt != null && job.updatedAt != null)
                            job.durationMillis += Math.max(0, Duration.between(Instant.parse(job.segmentStartedAt), Instant.parse(job.updatedAt)).toMillis());
                        job.segmentStartedAt = null;
                        job.state = "INTERRUPTED"; job.error = "服务重启，任务已中断；不会自动重复调用付费接口";
                        for (Product p : job.products) if (!"DONE".equals(p.state) && !"FAILED".equals(p.state))
                        { p.state = "INTERRUPTED"; p.incomplete = true; }
                        save(job);
                    }
                    if (repairDetailWarnings(job)) save(job);
                    history.put(job.id, summary(job));
                    jobs.put(job.id, job);
                    publish(job);
                }
            }
        }
        catch (Exception e) { throw new IllegalStateException("Cannot restore product scan tasks from " + this.storage, e); }
    }

    private void loadUsage()
    {
        synchronized (usageLock)
        {
            usageDate = LocalDate.now(ZoneId.of("Asia/Shanghai")); usageCount = 0;
            try
            {
                if (Files.exists(usageFile))
                {
                    var usage = JSON.parseObject(Files.readString(usageFile));
                    LocalDate saved = LocalDate.parse(usage.getString("date"));
                    if (saved.equals(usageDate))
                    {
                        usageCount = Math.max(0, usage.getIntValue("count"));
                        reservedCount = Math.max(0, usage.getIntValue("reserved"));
                    }
                }
            }
            catch (Exception e) { throw new IllegalStateException("无法读取每日调用计数，请检查 daily-usage.json", e); }
        }
    }

    /** 调用第三方前预占额度；并发任务共享同一把锁，避免超额调用。 */
    private boolean reserveProviderSlot()
    {
        synchronized (usageLock)
        {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if (!today.equals(usageDate)) { usageDate = today; usageCount = 0; reservedCount = 0; }
            if (usageCount + reservedCount >= dailyLimit) return false;
            reservedCount++;
            saveUsage();
            return true;
        }
    }

    /** 返回商品信息或明确返回商品已下架时，正式扣除预占额度。 */
    private void recordProviderSuccess()
    {
        synchronized (usageLock)
        {
            reservedCount = Math.max(0, reservedCount - 1);
            usageCount++;
            saveUsage();
        }
    }

    /** 第三方调用失败时释放预占额度。 */
    private void releaseProviderSlot()
    {
        synchronized (usageLock) { reservedCount = Math.max(0, reservedCount - 1); saveUsage(); }
    }

    private void saveUsage()
    {
        try
        {
            Path temp = storage.resolve("daily-usage.tmp");
            Files.writeString(temp, JSON.toJSONString(Map.of("date", usageDate.toString(), "count", usageCount, "reserved", reservedCount)));
            Files.move(temp, usageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (Exception e) { throw new IllegalStateException("每日调用计数保存失败，请检查任务目录权限", e); }
    }

    public Map<String, Object> usage()
    {
        synchronized (usageLock)
        {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if (!today.equals(usageDate)) { usageDate = today; usageCount = 0; }
            return Map.of("date", usageDate.toString(), "used", usageCount, "limit", dailyLimit);
        }
    }

    private static double threshold(Job job)
    {
        double value = job.rules == null ? 0.6 : job.rules.confidenceThreshold;
        return value == 0.4 || value == 0.5 || value == 0.6 ? value : 0.6;
    }

    /** 临时网络错误最多再试两次；明确的商品或权限错误立即结束。 */
    /** 仅缓存未命中时执行；预占额度和成功扣量均限定在真实接口调用范围内。 */
    private TaobaoProductInfo fetchAndCharge(Product product)
    {
        if (!reserveProviderSlot()) return null;
        TaobaoProductInfo result;
        try { result = fetchWithRetry(product); }
        catch (RuntimeException e)
        {
            if (e instanceof TaobaoFetchException fetchError
                    && fetchError.getCode() == TaobaoFetchException.Code.ITEM_UNAVAILABLE)
                recordProviderSuccess();
            else releaseProviderSlot();
            throw e;
        }
        recordProviderSuccess();
        return result;
    }

    private TaobaoProductInfo fetchWithRetry(Product product)
    {
        TaobaoFetchException last = null;
        for (int attempt = 0; attempt <= 2; attempt++)
        {
            try { return gateway.fetch(product.itemId, product.platform); }
            catch (TaobaoFetchException e)
            {
                last = e;
                if (!Set.of(TaobaoFetchException.Code.TIMEOUT, TaobaoFetchException.Code.NETWORK_ERROR,
                        TaobaoFetchException.Code.HTTP_ERROR, TaobaoFetchException.Code.PROVIDER_ERROR,
                        TaobaoFetchException.Code.RATE_LIMITED).contains(e.getCode()) || attempt == 2) throw e;
                try { Thread.sleep(800L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }

    private static void validateThreshold(double value)
    {
        if (value != 0.4 && value != 0.5 && value != 0.6)
            throw new ServiceException("低置信度阈值只能选择 60%、50% 或 40%");
    }

    /** 修正旧版缺少详情图及重复 URL 规则；不重新抓取，不清除其他未完成原因。 */
    private static boolean repairDetailWarnings(Job job)
    {
        boolean changed = false;
        for (Product p : job.products)
        {
            Set<String> obsolete = Set.of("详情图缺失或全部与主图重复，详情完整性待复核",
                    "接口未返回详情图，详情完整性待复核");
            if (!p.warnings.removeIf(obsolete::contains)) continue;
            boolean complete = "DONE".equals(p.state) && p.error == null
                    && p.pictures.stream().anyMatch(pic -> "MAIN".equals(pic.kind))
                    && p.pictures.stream().allMatch(pic -> "DONE".equals(pic.state) && pic.error == null
                        && pic.ocr != null && pic.ocr.lines.stream().noneMatch(line -> line.score < threshold(job)))
                    && p.warnings.stream().allMatch(w -> w.equals("检测范围为接口实际返回内容；未命中词库不代表平台合规或上游图片完整"));
            if (complete)
            {
                p.incomplete = false;
                p.verdict = !p.titleHits.isEmpty() || p.pictures.stream().anyMatch(pic -> !pic.hits.isEmpty() || pic.qrCodes > 0)
                        ? "MATCHED" : "CLEAR";
            }
            changed = true;
        }
        return changed;
    }

    /** 校验输入和服务状态后入队；重复商品 ID 合并检测，原 CSV 仍保留重复行。 */
    public synchronized Job create(long owner, Request request)
    {
        if (request != null && request.executionListId != null)
        {
            Job existing = executionTask(owner, request.executionListId);
            if (existing != null) return existing;
        }
        if (request == null || request.items == null || request.items.isEmpty() || request.items.size() > 10_000)
            throw new ServiceException("每次请导入 1 至 10,000 条商品记录（不含表头）");
        Request rules = JSON.parseObject(JSON.toJSONString(request, JSONWriter.Feature.LargeObject), Request.class);
        validateThreshold(rules.confidenceThreshold);
        if (ScanRules.words(rules.titleWords).isEmpty() || (ScanRules.words(rules.imageWords).isEmpty() && !rules.detectPhones && !rules.detectQrCodes))
            throw new ServiceException("请填写标题过滤词，并填写图片过滤词、手机号检测或二维码检测");
        if (!Set.of("taobao", "1688").contains(rules.platform == null ? "" : rules.platform))
            throw new ServiceException("请先选择商品平台：淘宝/天猫或1688");
        Set<String> ids = new LinkedHashSet<>();
        for (String input : rules.items) ids.add(ScanRules.itemId(input, rules.platform));
        if (rules.sourceCsv != null)
        {
            List<String> imported = ScanCsvSource.parse(rules.sourceCsv).items(rules.platform);
            if (!imported.equals(rules.items.stream().map(input -> ScanRules.itemId(input, rules.platform)).toList()))
                throw new ServiceException("商品列表与导入 CSV 不一致，请重新导入文件");
        }
        rules.items = List.copyOf(ids);
        for (Job job : jobs.values()) synchronized (job)
        {
            if (job.ownerId == owner && active(job)) throw new ServiceException("你已有进行中的任务，请等待完成或先停止");
        }
        remoteWorker.checkAvailable();
        gateway.checkReady(!remoteWorker.enabled());
        Job job = new Job(); job.id = UUID.randomUUID().toString(); job.ownerId = owner;
        job.createdAt = Instant.now().toString(); job.rules = rules;
        captureWhitelist(job);
        for (String id : ids) { Product product = new Product(); product.platform = rules.platform; product.itemId = id; job.products.add(product); }
        synchronized (job)
        {
            save(job); jobs.put(job.id, job);
            try { executor.execute(() -> run(job)); }
            catch (java.util.concurrent.RejectedExecutionException e)
            {
                job.state = "FAILED"; job.error = "任务队列已满，尚未调用商品接口"; save(job);
                throw new ServiceException(job.error);
            }
            return response(job);
        }
    }

    /** 按清单查找固定检测任务；原有任务权限仍按创建用户检查。 */
    public synchronized Job executionTask(long owner, Long listId)
    {
        if (listId == null) return null;
        Job found = jobs.values().stream().filter(j -> j.rules != null && listId.equals(j.rules.executionListId))
                .min(Comparator.comparing(j -> j.createdAt)).orElse(null);
        if (found == null) return null;
        Job job = owned(owner, found.id);
        synchronized (job)
        {
            Job view = response(job);
            view.rules.titleWords = job.rules.titleWords; view.rules.imageWords = job.rules.imageWords;
            view.rules.detectPhones = job.rules.detectPhones; view.rules.detectQrCodes = job.rules.detectQrCodes;
            return view;
        }
    }

    /** 继续未执行完的商品，沿用原始清单快照和规则，已完成及获取失败项不重复执行。 */
    public synchronized Job resume(long owner, String id)
    {
        Job job = owned(owner, id);
        synchronized (job)
        {
            if (active(job)) return response(job);
            if (job.recheckItemIds.isEmpty() && job.products.stream().noneMatch(p -> !Set.of("DONE", "FAILED").contains(p.state)))
                throw new ServiceException("检测已结束，没有可继续的商品；获取异常请使用重新获取");
            if (history.values().stream().anyMatch(v -> jobs.get(v.id()).ownerId == owner
                    && Set.of("QUEUED", "RUNNING").contains(v.state())))
                throw new ServiceException("你已有进行中的任务，请等待完成或先停止");
            remoteWorker.checkAvailable();
            gateway.checkReady(!remoteWorker.enabled());
            captureWhitelist(job);
            String oldState = job.state, oldError = job.error;
            boolean oldCancel = job.cancelRequested;
            List<String> oldIds = job.retryItemIds;
            String oldOnly = job.retryOnlyItemId;
            job.state = "QUEUED"; job.error = null; job.cancelRequested = false;
            job.retryItemIds = new ArrayList<>(); job.retryOnlyItemId = null;
            try { save(job); executor.execute(() -> run(job)); }
            catch (RuntimeException e)
            {
                job.state = oldState; job.error = oldError; job.cancelRequested = oldCancel;
                job.retryItemIds = oldIds; job.retryOnlyItemId = oldOnly; save(job);
                throw new ServiceException("任务暂时无法入队，请稍后继续检测");
            }
            return response(job);
        }
    }

    /** 修改已完成任务的置信度阈值并重新计算低置信度状态，不重新调用商品接口。 */
    public Job updateThreshold(long owner, String id, double value)
    {
        validateThreshold(value);
        Job job = owned(owner, id);
        synchronized (job)
        {
            if (active(job)) throw new ServiceException("请在任务结束后调整置信度阈值");
            job.rules.confidenceThreshold = value;
            for (Product p : job.products)
            {
                if (!"DONE".equals(p.state)) continue;
                boolean pictureProblem = p.error != null || p.pictures.stream().anyMatch(pic ->
                        !"DONE".equals(pic.state) || pic.error != null || pic.ocr == null);
                boolean low = p.pictures.stream().anyMatch(pic -> pic.ocr != null && pic.ocr.lines.stream().anyMatch(line -> line.score < value));
                boolean other = p.warnings.stream().anyMatch(w -> !w.equals("检测范围为接口实际返回内容；未命中词库不代表平台合规或上游图片完整") && !w.contains("有低置信度文字"));
                p.incomplete = pictureProblem || low || other;
                p.warnings.removeIf(w -> w.contains("有低置信度文字"));
                if (low) p.warnings.add("有低置信度文字，请人工核查");
                p.verdict = !p.titleHits.isEmpty() || p.pictures.stream().anyMatch(pic -> !pic.hits.isEmpty() || pic.qrCodes > 0) ? "MATCHED" : p.incomplete ? "REVIEW" : "CLEAR";
            }
            save(job);
            return response(job);
        }
    }

    /** 历史列表读取最近保存的摘要，不复制 OCR 数据，也不等待任务处理锁。 */
    public List<TaskSummary> list(long owner)
    {
        return jobs.values().stream().filter(job -> job.ownerId == owner)
                .map(job -> history.get(job.id)).filter(summary -> summary != null)
                .sorted(Comparator.comparing(TaskSummary::createdAt).reversed()).limit(50).toList();
    }

    /** 返回当前账号历史上已成功获取商品信息的 ID，供导入前过滤重复检测。 */
    public Set<String> checkedIds(long owner, String platform)
    {
        if (!Set.of("taobao", "1688").contains(platform)) throw new ServiceException("请先选择商品平台：淘宝/天猫或1688");
        Set<String> ids = new java.util.HashSet<>();
        for (Job job : jobs.values()) if (job.ownerId == owner && job.rules != null && platform.equals(job.rules.platform))
            synchronized (job) { for (Product p : job.products) if (p.providerFetched || (!p.importedTitleChecked && p.title != null && !p.title.isBlank())) ids.add(p.itemId); }
        return ids;
    }

    private static TaskSummary summary(Job job)
    {
        return new TaskSummary(job.id, job.createdAt, job.state,
                job.rules == null || job.rules.platform == null ? "taobao" : job.rules.platform,
                job.products.size(), job.startedAt, job.completedAt, job.durationMillis);
    }

    public Job get(long owner, String id)
    {
        Job job = owned(owner, id); synchronized (job) { return copy(job); }
    }

    /**
     * 按结果类型筛选后分页，保留全任务统计；仅复制当前页的图片和 OCR 证据。
     *
     * @param owner 当前用户ID
     * @param id 任务ID
     * @param pageNum 页码，从1开始
     * @param pageSize 每页条数，最多100条
     * @param filter 结果类型
     * @return 当前页及全任务统计
     */
    public TaskPage page(long owner, String id, int pageNum, int pageSize, String filter)
    {
        if (pageNum < 1 || pageSize < 1 || pageSize > 100)
            throw new ServiceException("分页参数不正确，每页最多100条");
        if (!Set.of("ALL", "MATCHED", "REVIEW", "INCOMPLETE", "PROVIDER_ERROR", "ITEM_UNAVAILABLE", "ELIGIBLE").contains(filter))
            throw new ServiceException("结果类型不正确");
        Job source = snapshot(owner, id).overview();
        // 快照不可变，统计和当前页来自同一个版本。
        TaskPage result = new TaskPage();
        List<Product> filtered = new ArrayList<>();
        result.productCount = source.products.size();
        for (int i = 0; i < source.products.size(); i++)
        {
            Product p = source.products.get(i);
            boolean providerError = p.error != null && PROVIDER_ERRORS.contains(p.error);
            boolean unavailable = "ITEM_UNAVAILABLE".equals(p.error);
            boolean matched = "MATCHED".equals(p.verdict);
            // 命中已经确定不合格，低置信度或图片失败仅作为证据，不再重复计入待复核。
            boolean review = !providerError && !unavailable && !matched && "DONE".equals(p.state) && p.incomplete;
            boolean incomplete = !providerError && !unavailable && !"DONE".equals(p.state);
            boolean passed = ScanRules.exportable(p);
            if (providerError) result.providerError++;
            if (retryable(p)) result.retryable++;
            if (unavailable) result.unavailable++;
            if (matched) result.matched++;
            if (review) result.review++;
            if (incomplete) result.incomplete++;
            if (passed) result.passed++;
            if ("DONE".equals(p.state) || "FAILED".equals(p.state)) result.processed++;
            if ("FAILED".equals(p.state)) result.failed++;
            int processedImages = (int) p.pictures.stream()
                    .filter(pic -> "DONE".equals(pic.state) || "FAILED".equals(pic.state)).count();
            result.totalImages += p.pictures.size();
            result.processedImages += processedImages;
            if (active(source) && result.currentIndex == 0
                    && ("FETCHING".equals(p.state) || "SCANNING".equals(p.state)))
            {
                result.currentIndex = i + 1;
                result.currentItemId = p.itemId;
                result.currentState = p.state;
                result.currentImages = p.pictures.size();
                result.currentProcessedImages = processedImages;
            }
            if (switch (filter) {
                case "MATCHED" -> matched;
                case "REVIEW" -> review;
                case "INCOMPLETE" -> incomplete;
                case "PROVIDER_ERROR" -> providerError;
                case "ITEM_UNAVAILABLE" -> unavailable;
                case "ELIGIBLE" -> passed;
                default -> true;
            }) filtered.add(p);
        }
        result.total = filtered.size();
        result.pageSize = pageSize;
        result.pageNum = Math.min(pageNum, Math.max(1, (result.total + pageSize - 1) / pageSize));
        int from = (result.pageNum - 1) * pageSize;
        Job view = new Job();
        view.id = source.id; view.ownerId = source.ownerId;
        view.createdAt = source.createdAt; view.updatedAt = source.updatedAt;
        view.startedAt = source.startedAt; view.completedAt = source.completedAt;
        view.durationMillis = source.durationMillis; view.state = source.state;
        view.segmentStartedAt = source.segmentStartedAt;
        view.resumable = source.resumable || source.products.stream().anyMatch(p -> !Set.of("DONE", "FAILED").contains(p.state));
        view.cancelRequested = owned(owner, id).cancelRequested; view.error = source.error;
        view.ruleVersion = source.ruleVersion;
        // 原始导入内容和完整词库仍保存在任务快照中，分页展示只需平台和阈值。
        view.rules = new Request();
        view.rules.wordLibraryId = source.recheckRules == null ? source.rules.wordLibraryId : source.recheckRules.wordLibraryId;
        view.rules.executionListId = source.rules.executionListId;
        view.rules.executionMode = source.rules.executionMode;
        view.rules.platform = source.rules.platform;
        view.rules.confidenceThreshold = source.rules.confidenceThreshold;
        view.products = filtered.subList(from, Math.min(from + pageSize, result.total));
        result.job = copy(view);
        return result;
    }

    /** 图片点击后才返回完整 OCR 证据；归属和下标均在服务端校验。 */
    public Picture evidence(long owner, String id, String itemId, int index)
    {
        return JSON.parseObject(JSON.toJSONString(publishedPicture(owner, id, itemId, index)), Picture.class);
    }

    /** 内部只读引用；预览读取只取文件标识，避免重复复制 OCR。 */
    private Picture publishedPicture(long owner, String id, String itemId, int index)
    {
        ReadSnapshot snapshot = snapshot(owner, id);
        for (int i = 0; i < snapshot.overview().products.size(); i++)
        {
            if (!snapshot.overview().products.get(i).itemId.equals(itemId)) continue;
            List<Picture> pictures = snapshot.evidence().get(i);
            if (index < 0 || index >= pictures.size()) throw new ServiceException("图片不存在");
            return pictures.get(index);
        }
        throw new ServiceException("商品不存在");
    }

    private ReadSnapshot snapshot(long owner, String id)
    {
        ReadSnapshot snapshot = snapshots.get(id);
        if (snapshot == null || snapshot.overview().ownerId != owner)
            throw new ServiceException("任务不存在或无权访问");
        return snapshot;
    }

    /** 操作响应只返回任务标识和状态，页面随后读取当前页。 */
    private static Job response(Job source)
    {
        Job view = new Job();
        view.id = source.id; view.ownerId = source.ownerId;
        view.createdAt = source.createdAt; view.updatedAt = source.updatedAt;
        view.startedAt = source.startedAt; view.completedAt = source.completedAt;
        view.durationMillis = source.durationMillis; view.state = source.state;
        view.segmentStartedAt = source.segmentStartedAt;
        view.resumable = !source.recheckItemIds.isEmpty() || source.products.stream().anyMatch(p -> !Set.of("DONE", "FAILED").contains(p.state));
        view.cancelRequested = source.cancelRequested; view.error = source.error;
        view.ruleVersion = source.ruleVersion;
        view.rules = new Request();
        view.rules.wordLibraryId = source.recheckRules == null ? source.rules.wordLibraryId : source.recheckRules.wordLibraryId;
        view.rules.executionListId = source.rules.executionListId;
        view.rules.executionMode = source.rules.executionMode;
        view.rules.platform = source.rules.platform;
        view.rules.confidenceThreshold = source.rules.confidenceThreshold;
        return view;
    }

    /** 保存成功后发布只读查询版本。OCR 在识别后不再修改，可共享而无需反复序列化。 */
    private void publish(Job source)
    {
        Job view = response(source);
        List<List<Picture>> evidence = new ArrayList<>();
        for (Product p : source.products)
        {
            Product row = new Product();
            row.platform = p.platform; row.itemId = p.itemId; row.title = p.title;
            row.state = p.state; row.verdict = p.verdict; row.error = p.error;
            row.retryCount = p.retryCount; row.providerFetched = p.providerFetched;
            row.review = p.review; row.reviewNote = p.reviewNote; row.reviewedAt = p.reviewedAt;
            row.incomplete = p.incomplete; row.warnings = List.copyOf(p.warnings);
            row.titleHits = List.copyOf(p.titleHits);
            List<Picture> details = new ArrayList<>();
            for (Picture pic : p.pictures)
            {
                Picture detail = pictureSummary(pic, threshold(source));
                detail.ocr = pic.ocr;
                details.add(detail);
                row.pictures.add(pictureSummary(pic, threshold(source)));
            }
            evidence.add(List.copyOf(details));
            view.products.add(row);
        }
        snapshots.put(source.id, new ReadSnapshot(view, List.copyOf(evidence)));
    }

    private static Picture pictureSummary(Picture pic, double threshold)
    {
        Picture view = new Picture();
        view.kind = pic.kind; view.index = pic.index; view.url = pic.url;
        view.state = pic.state; view.error = pic.error; view.previewKey = pic.previewKey;
        view.hits = List.copyOf(pic.hits); view.qrCodes = pic.qrCodes;
        view.lowConfidence = pic.ocr != null && pic.ocr.lines.stream().anyMatch(line -> line.score < threshold);
        return view;
    }

    /** 设置停止标记，在处理边界停止后续工作；不会撤销已发出的请求。 */
    public Job cancel(long owner, String id)
    {
        Job job = owned(owner, id);
        // 控制信号不能等待整单写盘锁；最终状态由运行线程统一落盘。
        if (active(job)) job.cancelRequested = true;
        Job view = response(snapshot(owner, id).overview());
        view.cancelRequested = job.cancelRequested;
        return view;
    }

    /** 重新尝试获取单个商品；和批量重试共用资格校验与任务队列，不限制人工重试次数。 */
    public synchronized Job retryProduct(long owner, String id, String itemId)
    {
        return enqueueRetry(owner, id, itemId);
    }

    /** 一键重试整个任务中的获取异常商品，不受当前页或筛选条件影响。 */
    public synchronized Job retryFailedProducts(long owner, String id)
    {
        return enqueueRetry(owner, id, null);
    }

    /** 返回重检表单所需的规则，不返回原始CSV或商品明细。 */
    public Request recheckRules(long owner, String id)
    {
        Job job = owned(owner, id);
        synchronized (job)
        {
            Request source = job.recheckRules == null ? job.rules : job.recheckRules;
            Request result = new Request();
            result.wordLibraryId = source.wordLibraryId;
            result.titleWords = source.titleWords; result.imageWords = source.imageWords;
            result.detectPhones = source.detectPhones; result.detectQrCodes = source.detectQrCodes;
            return result;
        }
    }

    /** 从选中词库读取最新过滤词；无词库时使用用户在重检弹窗中明确提交的正文。 */
    private Request latestRecheckRules(Job job, Request input)
    {
        Request result = new Request();
        if (input == null)
        {
            result.wordLibraryId = job.recheckRules == null ? job.rules.wordLibraryId : job.recheckRules.wordLibraryId;
            if (result.wordLibraryId == null) throw new ServiceException("请先选择最新过滤词库或填写本次重检过滤词");
        }
        else result.wordLibraryId = input.wordLibraryId;
        if (result.wordLibraryId != null)
        {
            var library = productWordLibraryService.selectProductWordLibraryById(result.wordLibraryId);
            if (library == null) throw new ServiceException("过滤词库不存在，请重新选择");
            result.titleWords = library.getTitleWords(); result.imageWords = library.getImageWords();
            result.detectPhones = Boolean.TRUE.equals(library.getDetectPhones());
            result.detectQrCodes = Boolean.TRUE.equals(library.getDetectQrCodes());
        }
        else
        {
            result.titleWords = input.titleWords; result.imageWords = input.imageWords;
            result.detectPhones = input.detectPhones; result.detectQrCodes = input.detectQrCodes;
        }
        if (ScanRules.words(result.titleWords).isEmpty()
                || (ScanRules.words(result.imageWords).isEmpty() && !result.detectPhones && !result.detectQrCodes))
            throw new ServiceException("请填写标题过滤词，并填写图片过滤词或开启手机号、二维码检测");
        return result;
    }

    /** 提取整个任务的命中项，使用最新白名单重新检测，保留其他商品结果。 */
    public synchronized Job recheckMatched(long owner, String id, Request input)
    {
        Job job = owned(owner, id);
        synchronized (job)
        {
            if (history.values().stream().anyMatch(v -> jobs.get(v.id()).ownerId == owner
                    && Set.of("QUEUED", "RUNNING").contains(v.state())))
                throw new ServiceException("你已有进行中的任务，请等待完成或先停止");
            if (!job.recheckItemIds.isEmpty()) throw new ServiceException("还有未完成的命中项重检，请点击继续任务");
            List<String> ids = job.products.stream().filter(p -> "MATCHED".equals(p.verdict)).map(p -> p.itemId).toList();
            if (ids.isEmpty()) throw new ServiceException("没有需要重新检测的命中项");
            remoteWorker.checkAvailable();
            gateway.checkReady(!remoteWorker.enabled());
            Request latestRules = latestRecheckRules(job, input);
            Job latest = new Job();
            captureWhitelist(latest);
            String oldState = job.state, oldError = job.error;
            boolean oldCancel = job.cancelRequested;
            Request oldRules = job.recheckRules;
            List<WhitelistRule> oldWhitelist = job.recheckWhitelist;
            List<String> oldRetryIds = job.retryItemIds;
            String oldOnly = job.retryOnlyItemId;
            job.recheckItemIds = new ArrayList<>(ids); job.recheckWhitelist = latest.whitelistRules;
            job.recheckRules = latestRules;
            job.retryItemIds = new ArrayList<>(); job.retryOnlyItemId = null;
            job.state = "QUEUED"; job.error = null; job.cancelRequested = false;
            try { save(job); executor.execute(() -> run(job)); }
            catch (RuntimeException e)
            {
                job.recheckItemIds = new ArrayList<>(); job.recheckWhitelist = oldWhitelist;
                job.recheckRules = oldRules;
                job.retryItemIds = oldRetryIds; job.retryOnlyItemId = oldOnly;
                job.state = oldState; job.error = oldError; job.cancelRequested = oldCancel;
                save(job);
                throw new ServiceException("任务暂时无法入队，请稍后重试");
            }
            return response(job);
        }
    }

    /** 只记录目标ID；真正开始调用前才清理错误并记录人工重试次数。 */
    private Job enqueueRetry(long owner, String id, String itemId)
    {
        Job job = owned(owner, id);
        if (history.values().stream().anyMatch(v -> jobs.get(v.id()).ownerId == owner
                && Set.of("QUEUED", "RUNNING").contains(v.state())))
            throw new ServiceException("你已有进行中的任务，请等待完成或先停止");
        synchronized (job)
        {
            if (active(job)) throw new ServiceException("任务正在执行，请等待任务结束");
            if (!job.recheckItemIds.isEmpty()) throw new ServiceException("请先继续并完成命中项重检");
            List<String> ids = job.products.stream().filter(ProductScanService::retryable)
                    .filter(p -> itemId == null || itemId.equals(p.itemId)).map(p -> p.itemId).toList();
            if (ids.isEmpty()) throw new ServiceException("没有可重试的获取异常商品，已下架或非获取异常商品不参与");
            remoteWorker.checkAvailable();
            captureWhitelist(job);
            String oldState = job.state, oldError = job.error, oldOnly = job.retryOnlyItemId;
            List<String> oldIds = job.retryItemIds;
            boolean oldCancel = job.cancelRequested;
            job.retryItemIds = ids; job.retryOnlyItemId = null;
            job.state = "QUEUED"; job.error = null; job.cancelRequested = false;
            try
            {
                save(job);
                executor.execute(() -> run(job));
            }
            catch (RuntimeException e)
            {
                job.state = oldState; job.error = oldError; job.cancelRequested = oldCancel;
                job.retryItemIds = oldIds; job.retryOnlyItemId = oldOnly;
                save(job);
                if (e instanceof java.util.concurrent.RejectedExecutionException)
                    throw new ServiceException("任务队列已满，请稍后重试，未消耗重试次数");
                throw e;
            }
            return response(job);
        }
    }

    /** 保存人工决定；未完整检测的商品不能直接信任为通过，复核原因可留空。 */
    public Job review(long owner, String id, String itemId, Review review)
    {
        Job job = owned(owner, id); synchronized (job)
        {
            if (active(job)) throw new ServiceException("请在任务结束后复核");
            if (review == null || review.decision == null || !Set.of("NONE", "TRUSTED", "REJECTED").contains(review.decision))
                throw new ServiceException("无效的复核决定");
            Product p = job.products.stream().filter(v -> v.itemId.equals(itemId)).findFirst()
                    .orElseThrow(() -> new ServiceException("商品不存在"));
            if ("TRUSTED".equals(review.decision) && (p.incomplete || !"DONE".equals(p.state)))
                throw new ServiceException("存在缺图、识别失败或未完成内容，不能直接信任为通过");
            if (review.note != null && review.note.length() > 500) throw new ServiceException("复核原因最多 500 字");
            p.review = review.decision; p.reviewNote = review.note; p.reviewedAt = Instant.now().toString();
            save(job); return response(job);
        }
    }

    public byte[] image(long owner, String id, String itemId, int pictureIndex)
    {
        Picture picture = publishedPicture(owner, id, itemId, pictureIndex);
        String key = picture.previewKey;
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new ServiceException("图片预览尚未就绪");
        try {
            Path assets = storage.resolve("assets");
            if (!Files.exists(assets.resolve(key + ".jpg")) && remoteWorker.enabled())
                throw new ServiceException("当前为不传图测试模式，预览图保留在检测电脑，云端暂不可查看");
            return Files.readAllBytes(assets.resolve(key + ".jpg"));
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("预览文件不可用"); }
    }

    /** 通过项使用导入模板；全部项输出中文判定、命中位置和错误说明。 */
    public byte[] export(long owner, String id, boolean eligibleOnly)
    {
        Job job = get(owner, id);
        if (eligibleOnly) return exportTemplate(job);
        StringBuilder out = new StringBuilder("\uFEFF商品ID,商品链接,标题,执行状态,规则判定,人工复核,可导出通过,标题命中词,图片命中证据,未完成与错误,复核原因\r\n");
        for (Product p : job.products)
        {
            String evidence = p.pictures.stream().filter(pic -> !pic.hits.isEmpty())
                    .map(pic -> pictureName(pic) + "命中词【" + String.join("、", pic.hits) + "】").collect(java.util.stream.Collectors.joining(";"));
            String errors = p.pictures.stream().filter(pic -> pic.error != null)
                    .map(pic -> pictureName(pic) + "：" + chinese(pic.error)).collect(java.util.stream.Collectors.joining(";"));
            List<String> cells = java.util.Arrays.asList(p.itemId, productUrl(p),
                    p.title, chinese(p.state), chinese(p.verdict), chinese(p.review), ScanRules.exportable(p) ? "是" : "否",
                    p.titleHits.isEmpty() ? "标题未命中" : "标题命中词【" + String.join("、", p.titleHits) + "】", evidence.isEmpty() ? "暂无图片命中记录" : evidence, chinese(String.join("；", p.warnings.stream().filter(w -> !"MATCHED".equals(p.verdict) || !w.contains("低置信度")).toList())) + ";" + (p.error == null ? "" : chinese(p.error)) + errors, p.reviewNote);
            out.append(cells.stream().map(ScanRules::csv).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final List<String> TEMPLATE = List.of("序号", "关键词", "主图", "商品链接", "类目",
            "商品标题", "店铺链接", "价格", "销量", "评论数", "状态");

    /** 固定输出 11 列，优先使用导入列值并按商品 ID 去重；旧任务缺少 CSV 时用快照补列。 */
    private static byte[] exportTemplate(Job job)
    {
        StringBuilder out = new StringBuilder("\uFEFF" + String.join(",", TEMPLATE) + "\r\n");
        Map<String, Product> products = new HashMap<>();
        for (Product product : job.products) products.put(product.itemId, product);
        if (job.rules.sourceCsv != null)
        {
            var source = ScanCsvSource.parse(job.rules.sourceCsv);
            List<String> headers = source.header() ? source.rows().get(0).cells().stream().map(String::trim).toList() : List.of();
            int ordinal = 0;
            Set<String> exportedIds = new LinkedHashSet<>();
            for (var row : source.rows().subList(source.header() ? 1 : 0, source.rows().size()))
            {
                if (row.raw().isBlank()) continue;
                Product product = products.get(ScanRules.itemId(row.cells().get(source.column()).trim(), job.rules.platform == null ? "taobao" : job.rules.platform));
                if (product == null || !ScanRules.exportable(product)) continue;
                if (!exportedIds.add(product.itemId)) continue;
                ordinal++;
                List<String> cells = templateCells(product, ordinal);
                for (int i = 0; i < TEMPLATE.size(); i++)
                {
                    int column = headers.indexOf(TEMPLATE.get(i));
                    if (column >= 0 && column < row.cells().size()) cells.set(i, row.cells().get(column));
                }
                appendCsv(out, cells);
            }
        }
        else
        {
            for (int i = 0; i < job.products.size(); i++)
                if (ScanRules.exportable(job.products.get(i))) appendCsv(out, templateCells(job.products.get(i), i + 1));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> templateCells(Product p, int ordinal)
    {
        String main = p.pictures.stream().filter(pic -> "MAIN".equals(pic.kind)).map(pic -> pic.url).findFirst().orElse("");
        return new java.util.ArrayList<>(java.util.Arrays.asList(String.valueOf(ordinal), "", main,
                productUrl(p), p.categoryName, p.title, p.shopUrl,
                p.price, p.sales, p.commentCount, "成功"));
    }

    private static void appendCsv(StringBuilder out, List<String> cells)
    {
        out.append(cells.stream().map(ScanRules::csv).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
    }

    private static String productUrl(Product p)
    {
        return "1688".equals(p.platform) ? "https://detail.1688.com/offer/" + p.itemId + ".html"
                : "https://item.taobao.com/item.htm?id=" + p.itemId;
    }

    private static String pictureName(Picture pic)
    {
        return "第" + pic.index + "张" + ("MAIN".equals(pic.kind) ? "主图" : "详情图");
    }

    private static String chinese(String value)
    {
        if (value == null) return "";
        return switch (value)
        {
            case "QUEUED" -> "排队中"; case "PENDING" -> "待处理";
            case "RUNNING" -> "检测中"; case "FETCHING" -> "获取商品中"; case "SCANNING" -> "识别图片中"; case "SKIPPED" -> "命中词库";
            case "DONE", "COMPLETED" -> "完成"; case "FAILED" -> "失败";
            case "CANCELLED" -> "已停止"; case "INTERRUPTED" -> "服务重启中断";
            case "MATCHED" -> "命中词库"; case "CLEAR" -> "未命中"; case "REVIEW" -> "待复核";
            case "ITEM_UNAVAILABLE" -> "商品已下架";
            case "MISSING_API_CREDENTIALS", "PROVIDER_ACCESS_DENIED", "PROVIDER_ERROR", "RATE_LIMITED", "INVALID_RESPONSE", "TIMEOUT", "NETWORK_ERROR", "HTTP_ERROR", "RESPONSE_TOO_LARGE" -> "暂时无法获取商品信息";
            case "NONE" -> "未复核"; case "TRUSTED" -> "人工信任"; case "REJECTED" -> "人工排除";
            default -> {
                try { yield new TaobaoFetchException(TaobaoFetchException.Code.valueOf(value)).getMessage().replace("taobao.item_get", "淘宝商品详情接口").replace("HTTP", "网络").replace("MiB", "兆字节"); }
                catch (IllegalArgumentException ignored) { yield value.replace("OCR", "文字识别").replace("HTTP", "网络").replace("API", "接口"); }
            }
        };
    }

    public int executionCapacity() { return productConcurrency; }

    /** 本机模式默认三并发；远程模式使用独立的集群派发上限。 */
    private final java.util.concurrent.ExecutorService productExecutor;
    private final int productConcurrency;

    private void run(Job job)
    {
        long segmentStarted = System.currentTimeMillis();
        long previousDuration = job.durationMillis;
        try
        {
            synchronized (job)
            {
                job.state = "RUNNING";
                if (job.startedAt == null) job.startedAt = Instant.now().toString();
                job.completedAt = null;
                job.segmentStartedAt = Instant.ofEpochMilli(segmentStarted).toString();
                job.durationMillis = previousDuration;
                save(job);
            }
            // 同一任务中相同图片 URL 只识别一次，各商品仍保留自己的图片记录。
            Map<String, Cached> cache = new ConcurrentHashMap<>();
            boolean rechecking = !job.recheckItemIds.isEmpty();
            Request activeRules = rechecking && job.recheckRules != null ? job.recheckRules : job.rules;
            ScanRules.PreparedWords titleWords = remoteWorker.enabled() ? null : ScanRules.prepare(activeRules.titleWords),
                    imageWords = remoteWorker.enabled() ? null : ScanRules.prepare(activeRules.imageWords);
            Set<String> recheckIds = new java.util.HashSet<>(job.recheckItemIds);
            ScanWhitelist whitelist = remoteWorker.enabled() ? null : new ScanWhitelist(rechecking ? job.recheckWhitelist : job.whitelistRules);
            Map<String, List<String>> importedTitles = ScanCsvSource.titles(job.rules.sourceCsv, job.rules.platform);
            Set<String> retryIds = new java.util.HashSet<>(job.retryItemIds);
            if (job.retryOnlyItemId != null) retryIds.add(job.retryOnlyItemId);
            boolean retrying = !retryIds.isEmpty();
            var pending = new java.util.concurrent.ConcurrentLinkedQueue<Product>();
            for (Product p : job.products)
            {
                if (rechecking && !recheckIds.contains(p.itemId)) continue;
                if (retrying && !retryIds.contains(p.itemId)) continue;
                if (!retrying && !rechecking && Set.of("DONE", "FAILED").contains(p.state)) continue;
                pending.add(p);
            }
            var quotaReached = new java.util.concurrent.atomic.AtomicBoolean();
            if (remoteWorker.enabled()) {
                // Each submission handles ONE product. Other users' jobs can enter the shared pool between products.
                var completion = new java.util.concurrent.ExecutorCompletionService<Boolean>(productExecutor);
                java.util.function.Consumer<Product> submit = p -> completion.submit(() -> {
                    if (stopping(job) || quotaReached.get()) return false;
                    return processProduct(job, p, rechecking, retrying, activeRules, titleWords, imageWords,
                            whitelist, importedTitles, cache);
                });
                int inFlight = 0;
                for (int i = 0; i < productConcurrency; i++) {
                    Product p = pending.poll(); if (p == null) break; submit.accept(p); inFlight++;
                }
                java.util.concurrent.ExecutionException failure = null;
                while (inFlight > 0) {
                    try { if (!completion.take().get()) quotaReached.set(true); }
                    catch (java.util.concurrent.ExecutionException e) { quotaReached.set(true); failure = e; }
                    inFlight--;
                    if (!stopping(job) && !quotaReached.get()) {
                        Product p = pending.poll(); if (p != null) { submit.accept(p); inFlight++; }
                    }
                }
                if (failure != null) throw failure;
            } else {
            var workers = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
            for (int i = 0; i < productConcurrency; i++)
            {
                workers.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    while (!stopping(job) && !quotaReached.get())
                    {
                        Product p = pending.poll();
                        if (p == null) return;
                        if (!processProduct(job, p, rechecking, retrying, activeRules, titleWords, imageWords,
                                whitelist, importedTitles, cache)) quotaReached.set(true);
                    }
                }, productExecutor));
            }
            // 全部已领取商品结束后才能发布任务完成状态，不能提前汇总或允许再次执行。
            java.util.concurrent.CompletableFuture.allOf(workers.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            }
            synchronized (job)
            {
                job.retryOnlyItemId = null;
                job.retryItemIds = new ArrayList<>();
                job.state = stopping(job) ? "CANCELLED"
                        : remoteWorker.enabled() && job.products.stream().anyMatch(p -> "INTERRUPTED".equals(p.state))
                            ? "INTERRUPTED" : "COMPLETED";
                job.completedAt = Instant.now().toString();
                job.durationMillis = previousDuration + Math.max(0, System.currentTimeMillis() - segmentStarted);
                for (Product p : job.products) if ("PENDING".equals(p.state)) { p.state = "CANCELLED"; p.incomplete = true; }
                save(job);
            }
        }
        catch (Exception e)
        {
            synchronized (job)
            {
                job.state = "FAILED"; job.error = "任务处理或保存失败，请检查服务端存储";
                job.completedAt = Instant.now().toString();
                if (job.startedAt != null) job.durationMillis = previousDuration + Math.max(0, System.currentTimeMillis() - segmentStarted);
                save(job);
            }
        }
    }

    /**
     * 每个工作线程独占一个商品；同一商品内图片仍顺序处理，命中立即停止。
     *
     * @param job 所属检测任务
     * @param p 当前商品
     * @param rechecking 是否重新检测命中项
     * @param retrying 是否重试获取异常项
     * @param activeRules 本轮执行的过滤规则快照
     * @param titleWords 预处理后的标题过滤词
     * @param imageWords 预处理后的图片过滤词
     * @param whitelist 本轮白名单快照
     * @param importedTitles 按商品编号归集的导入标题
     * @param cache 同一任务各工作线程共享的图片识别缓存
     * @return 是否可以继续领取下一个商品；额度不足时停止领取
     */
    private boolean processProduct(Job job, Product p, boolean rechecking, boolean retrying,
            Request activeRules, ScanRules.PreparedWords titleWords, ScanRules.PreparedWords imageWords,
            ScanWhitelist whitelist, Map<String, List<String>> importedTitles, Map<String, Cached> cache)
    {
        try
        {
            if (remoteWorker.enabled())
            {
                // Copy only execution rules; do not serialize the whole execution-list CSV for every product.
                Request suppliedRules = new Request();
                suppliedRules.platform = activeRules.platform;
                suppliedRules.titleWords = activeRules.titleWords; suppliedRules.imageWords = activeRules.imageWords;
                suppliedRules.detectPhones = activeRules.detectPhones; suppliedRules.detectQrCodes = activeRules.detectQrCodes;
                suppliedRules.confidenceThreshold = activeRules.confidenceThreshold;
                List<WhitelistRule> suppliedWhitelist = rechecking ? job.recheckWhitelist : job.whitelistRules;
                remoteWorker.execute(p, suppliedRules, suppliedWhitelist,
                        importedTitles.getOrDefault(p.itemId, List.of()), rechecking, retrying,
                        () -> productInfoCacheService.getOrFetch(p.platform, p.itemId, () -> fetchAndCharge(p)),
                        storage.resolve("assets"),
                        () -> stopping(job), updated -> {
                            synchronized (job) {
                                if (stopping(job)) throw new java.util.concurrent.CancellationException();
                                p.title = updated.title; p.titleHits = updated.titleHits;
                                p.price = updated.price; p.categoryName = updated.categoryName; p.shopUrl = updated.shopUrl;
                                p.sales = updated.sales; p.commentCount = updated.commentCount;
                                p.providerFetched = updated.providerFetched; p.importedTitleChecked = updated.importedTitleChecked;
                                p.retryCount = updated.retryCount; p.review = updated.review;
                                p.reviewNote = updated.reviewNote; p.reviewedAt = updated.reviewedAt;
                                p.pictures = updated.pictures; p.state = updated.state;
                                p.verdict = updated.verdict; p.error = updated.error;
                                p.incomplete = updated.incomplete; p.warnings = updated.warnings;
                                // Aggregate frequent multi-machine progress; final results are ALWAYS durably saved before ACK.
                                if (Set.of("DONE", "FAILED", "INTERRUPTED").contains(p.state)
                                        || job.updatedAt == null || Duration.between(Instant.parse(job.updatedAt), Instant.now()).toMillis() >= 5000)
                                    save(job);
                            }
                        });
                if ("DAILY_LIMIT_EXCEEDED".equals(p.error)) {
                    synchronized (job) { job.error = p.error; save(job); }
                    return false;
                }
            }
            else
            {
                if (!ProductDetectionEngine.prepareCompiled(p, titleWords, whitelist,
                        importedTitles.getOrDefault(p.itemId, List.of()), rechecking, retrying,
                        () -> productInfoCacheService.getOrFetch(p.platform, p.itemId, () -> fetchAndCharge(p)),
                        job, () -> save(job), () -> job.error = "DAILY_LIMIT_EXCEEDED")) return false;
                if ("DONE".equals(p.state) && !p.titleHits.isEmpty()) return true;
                ProductDetectionEngine.executePrepared(p, activeRules, threshold(job), imageWords,
                        whitelist, cache, storage.resolve("assets"), gateway, job,
                        () -> stopping(job), () -> save(job));
            }
        }
        catch (ScanWorkerBroker.Unavailable e)
        {
            synchronized (job) {
                p.state = "INTERRUPTED"; p.incomplete = true; p.error = e.getMessage();
                job.error = e.getMessage(); save(job);
            }
            return false;
        }
        catch (java.util.concurrent.CancellationException e)
        {
            synchronized (job) {
                // 收尾统一保存，避免每个并发商品停止时各重写一次完整清单。
                if (!Set.of("DONE", "FAILED").contains(p.state)) {
                    p.state = "CANCELLED"; p.incomplete = true;
                }
            }
        }
        catch (Exception e)
        {
            synchronized (job)
            {
                p.state = stopping(job) ? "CANCELLED" : "FAILED"; p.incomplete = true;
                p.error = e instanceof TaobaoFetchException ? ((TaobaoFetchException) e).getCode().name() : "商品获取或任务处理失败";
                if (!stopping(job)) save(job);
            }
        }
        finally
        {
            if (rechecking) synchronized (job)
            {
                if (Set.of("DONE", "FAILED").contains(p.state))
                { job.recheckItemIds.remove(p.itemId); if (!stopping(job)) save(job); }
            }
        }
        return true;
    }

    /** 所有任务操作均检查归属，避免用户凭任务 ID 读取或修改他人数据。 */
    private Job owned(long owner, String id)
    {
        Job job = jobs.get(id);
        if (job == null || job.ownerId != owner) throw new ServiceException("任务不存在或无权访问");
        return job;
    }
    private static boolean active(Job job) { return "QUEUED".equals(job.state) || "RUNNING".equals(job.state); }
    private static boolean stopping(Job job) { return job.cancelRequested || Thread.currentThread().isInterrupted(); }
    private static Job copy(Job job) { return JSON.parseObject(JSON.toJSONString(job, JSONWriter.Feature.LargeObject), Job.class); }
    /** 先写临时文件再替换快照，减少中断时损坏历史数据的风险。 */
    private void save(Job job)
    {
        job.updatedAt = Instant.now().toString();
        Path temp = null;
        String stage = "create temporary file";
        try
        {
            temp = Files.createTempFile(storage, "scan-", ".tmp");
            stage = "serialize and write task";
            // 大清单含完整 CSV/OCR，可能超过 Fastjson 默认 64M 数组限制。
            // 直接写 UTF-8，避免先构造完整 JSON String 再转码；仍保留临时文件原子替换。
            try (var output = Files.newOutputStream(temp)) {
                JSON.writeTo(output, job, JSONWriter.Feature.LargeObject);
            }
            stage = "replace task snapshot";
            Files.move(temp, storage.resolve(job.id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            stage = "publish task in memory";
            history.put(job.id, summary(job));
            publish(job);
        }
        catch (Exception e) {
            log.error("Product scan save failed: stage={}, directory={}, taskId={}", stage, storage, job.id, e);
            ServiceException failure = new ServiceException("任务保存失败，请查看服务器日志中的具体原因");
            failure.initCause(e);
            throw failure;
        }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
    }
    @PreDestroy
    public void close()
    {
        for (Job job : jobs.values()) job.cancelRequested = true;
        executor.shutdown();
        productExecutor.shutdown();
    }

}
