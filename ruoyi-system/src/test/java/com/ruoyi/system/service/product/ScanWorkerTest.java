package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.system.service.product.ScanModels.*;
import static com.ruoyi.system.service.product.ScanWorkerProtocol.*;

class ScanWorkerTest {
    @TempDir Path dir;
    private static final String TOKEN = "test-worker-secret-0123456789abcdef";
    Product product() {
        Product p = new Product(); p.itemId = "123"; p.title = "普通商品"; p.providerFetched = true;
        for (int i = 0; i < 3; i++) { Picture pic = new Picture(); pic.index = i+1; pic.kind = "MAIN"; pic.url = "https://img.alicdn.com/"+i+".jpg"; p.pictures.add(pic); }
        return p;
    }
    ScanGateway gateway(String text, double confidence, int qr, AtomicInteger count) {
        return new ScanGateway("", "") {
            public Inspection inspect(String url) {
                count.incrementAndGet(); Ocr ocr = new Ocr(); ocr.width = 100; ocr.height = 100; ocr.engine = "test";
                Line line = new Line(); line.text = text; line.score = confidence;
                line.box = List.of(List.of(0.0,0.0),List.of(50.0,0.0),List.of(50.0,50.0),List.of(0.0,50.0)); ocr.lines.add(line);
                return new Inspection(ocr, new byte[]{(byte)0xff,(byte)0xd8,1,2,3}, qr);
            }
        };
    }
    void run(Product p, Request r, List<WhitelistRule> whitelist, ScanGateway gateway) {
        ProductDetectionEngine.execute(p,r,whitelist,new HashMap<>(),dir,gateway,p,()->false,()->{});
    }
    @Test void sameEnginePreservesHitsWhitelistAndEarlyStop() {
        Request r = new Request(); r.imageWords = "163"; AtomicInteger calls = new AtomicInteger();
        Product p = product(); run(p,r,List.of(),gateway("CR1632",.99,0,calls));
        assertEquals("MATCHED",p.verdict); assertEquals(1,calls.get()); assertEquals("SKIPPED",p.pictures.get(1).state);
        WhitelistRule allow = new WhitelistRule(); allow.filterWord = "163"; allow.matchType = "WORD"; allow.matchContent = "CR1632";
        p = product(); run(p,r,List.of(allow),gateway("CR1632",.99,0,calls)); assertEquals("CLEAR",p.verdict);
        p = product(); p.titleHits = List.of("标题命中"); int before = calls.get();
        run(p,r,List.of(),gateway("ok",.99,0,calls)); assertEquals(before,calls.get()); assertEquals("MATCHED",p.verdict);
    }
    @Test void failuresLowConfidencePhoneQrAndCancelDoNotPass() {
        Request r = new Request(); r.imageWords = "过滤词"; AtomicInteger calls = new AtomicInteger();
        Product p = product(); run(p,r,List.of(),gateway("普通字",.55,0,calls)); assertEquals("REVIEW",p.verdict);
        p = product(); run(p,r,List.of(),new ScanGateway("", "") { public Inspection inspect(String u) { throw new IllegalStateException(); }});
        assertTrue(p.incomplete); assertEquals("REVIEW",p.verdict);
        p = product(); r.detectPhones = true; run(p,r,List.of(),gateway("13812345678",.99,0,calls)); assertEquals("MATCHED",p.verdict);
        p = product(); r.detectQrCodes = true; run(p,r,List.of(),gateway("普通字",.99,1,calls)); assertEquals("MATCHED",p.verdict);
        p = product(); ProductDetectionEngine.execute(p,r,List.of(),new HashMap<>(),dir,gateway("ok",.99,0,calls),p,()->true,()->{});
        assertEquals("CANCELLED",p.state); assertTrue(p.incomplete);
    }
    Message message(Input input, String worker) {
        Message m = new Message(); m.workerId = worker; m.session = "session-"+worker; m.capacity=4;
        if (input != null) { m.taskId = input.taskId; m.lease = input.lease; } return m;
    }
    Input claim(ScanWorkerBroker broker, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { Input input = broker.claim(message(null,id)); if (input != null) return input; Thread.sleep(10); }
        throw new AssertionError("No task assigned");
    }
    @Test void brokerAuthExclusiveLeaseDurableAckAndDuplicateSubmission() throws Exception {
        ScanWorkerBroker broker = new ScanWorkerBroker(true,TOKEN);
        assertFalse(broker.authenticate(null)); assertFalse(broker.authenticate("bad")); assertTrue(broker.authenticate(TOKEN));
        assertThrows(Exception.class,broker::checkAvailable);
        AtomicInteger commits = new AtomicInteger(); AtomicBoolean failSave = new AtomicBoolean(true);
        Product p = product(); p.titleHits = List.of("命中"); p.state = "SCANNING";
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = exec.submit(() -> {
                try { broker.execute(p,new Request(),List.of(),dir,()->false,result -> {
                    if (failSave.getAndSet(false)) throw new IllegalStateException("disk write failed");
                    commits.incrementAndGet();
                }); } catch(Exception e) { throw new RuntimeException(e); }
            });
            Input input = claim(broker,"desktop-01"); assertNull(broker.claim(message(null,"desktop-02")));
            Message report = message(input,"desktop-01"); assertTrue(broker.heartbeat(report).expiresAt >= input.expiresAt);
            report.product = input.product; report.product.state = "DONE"; report.product.verdict = "MATCHED";
            assertThrows(IllegalStateException.class,()->broker.complete(report)); assertFalse(work.isDone());
            assertTrue(broker.complete(report).accepted); work.get(5,TimeUnit.SECONDS);
            assertTrue(broker.complete(report).accepted); assertEquals(1,commits.get());
            report.workerId="desktop-02"; assertThrows(ScanWorkerBroker.Conflict.class,()->broker.complete(report));
        } finally { exec.shutdownNow(); }
    }
    @Test void cancellationRejectsLateResult() throws Exception {
        ScanWorkerBroker broker = new ScanWorkerBroker(true,TOKEN); AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = exec.submit(() -> {
                assertThrows(CancellationException.class,()->broker.execute(product(),new Request(),List.of(),dir,cancelled::get,p -> fail("must not save cancelled result")));
            });
            Input input = claim(broker,"desktop-01"); cancelled.set(true);
            Message m = message(input,"desktop-01"); m.product=input.product; m.product.state="DONE"; m.product.verdict="CLEAR";
            assertThrows(ScanWorkerBroker.Conflict.class,()->broker.complete(m)); work.get(5,TimeUnit.SECONDS);
        } finally { exec.shutdownNow(); }
    }
    @Test void expiredLeaseCanBeReassignedButOldWorkerCannotComplete() throws Exception {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        ScanWorkerBroker broker = new ScanWorkerBroker(true,TOKEN,now::get);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Product p = product(); p.titleHits = List.of("hit");
            Future<?> work = exec.submit(() -> {
                try { broker.execute(p,new Request(),List.of(),dir,()->false,result -> {}); }
                catch(Exception e) { throw new RuntimeException(e); }
            });
            Input old = claim(broker,"old-worker"); now.addAndGet(120001);
            Input fresh = claim(broker,"new-worker"); assertNotEquals(old.lease,fresh.lease);
            Message stale = message(old,"old-worker"); stale.product=old.product; stale.product.state="DONE"; stale.product.verdict="MATCHED";
            assertThrows(ScanWorkerBroker.Conflict.class,()->broker.complete(stale));
            Message result = message(fresh,"new-worker"); result.product=fresh.product; result.product.state="DONE"; result.product.verdict="MATCHED";
            assertTrue(broker.complete(result).accepted); work.get(5,TimeUnit.SECONDS);
        } finally { exec.shutdownNow(); }
    }
    @Test void previewHashRequiredButUploadNotRequiredForComparison() throws Exception {
        ScanWorkerBroker broker = new ScanWorkerBroker(true,TOKEN);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Product p = product(); p.pictures = new ArrayList<>(List.of(p.pictures.get(0)));
            Future<?> work = exec.submit(() -> {
                try { broker.execute(p,new Request(),List.of(),dir,()->false,result -> {}); }
                catch(Exception e) { throw new RuntimeException(e); }
            });
            Input input = claim(broker,"desktop-01");
            Message report = message(input,"desktop-01"); report.product = input.product;
            report.product.state="DONE";report.product.verdict="CLEAR";
            Picture pic=report.product.pictures.get(0);pic.state="DONE";pic.ocr=new Ocr();
            assertThrows(IllegalArgumentException.class,()->broker.complete(report));
            Message upload=message(input,"desktop-01");upload.artifact=new byte[]{(byte)0xff,(byte)0xd8,1,2,3};
            upload.artifactKey=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(upload.artifact));
            pic.previewKey=upload.artifactKey;
            assertFalse(Files.exists(dir.resolve(pic.previewKey + ".jpg")));
            assertTrue(broker.complete(report).accepted);work.get(5,TimeUnit.SECONDS);
        } finally {exec.shutdownNow();}
    }
    @Test void oversizedHistoryCanRestartSaveAndCopyWithoutLosingSourceCsv() throws Exception {
        Job job = new Job(); job.id = "large-history"; job.state = "RUNNING"; job.rules = new Request();
        // 引号转义后 JSON 超过 64M，复现线上 JSONLargeObjectException。
        job.rules.sourceCsv = "\"".repeat(34 * 1024 * 1024);
        job.products.add(product());
        assertThrows(com.alibaba.fastjson2.JSONLargeObjectException.class, () -> JSON.toJSONString(job));
        Path snapshot = dir.resolve(job.id + ".json");
        try (var output = Files.newOutputStream(snapshot)) {
            JSON.writeTo(output, job, com.alibaba.fastjson2.JSONWriter.Feature.LargeObject);
        }
        ProductScanService service = new ProductScanService(dir.toString(), "", "", 5000, 3, true, 32);
        try {
            Job restored = JSON.parseObject(Files.readString(snapshot), Job.class);
            assertEquals("INTERRUPTED", restored.state);
            assertEquals(job.rules.sourceCsv, restored.rules.sourceCsv);
            Job copied = service.get(0, job.id);
            assertEquals(job.rules.sourceCsv, copied.rules.sourceCsv);
            assertEquals(3, copied.products.get(0).pictures.size());
        } finally { service.close(); }
    }
    @Test void restartPersistsInterruptedTaskAndRetainsProducts() throws Exception {
        Job job = new Job(); job.id = "restart"; job.state = "RUNNING"; job.rules = new Request();
        job.products.add(product());
        Path snapshot = dir.resolve("restart.json");
        Files.writeString(snapshot, JSON.toJSONString(job));
        ProductScanService service = new ProductScanService(dir.toString(), "", "", 5000, 3, true, 32);
        try {
            Job restored = JSON.parseObject(Files.readString(snapshot), Job.class);
            assertEquals("INTERRUPTED", restored.state);
            assertEquals("123", restored.products.get(0).itemId);
            assertEquals(3, restored.products.get(0).pictures.size());
        } finally { service.close(); }
    }
    @Test void saveFailureRetainsUnderlyingCauseInsteadOfAssumingDiskFailure() throws Exception {
        ProductScanService service = new ProductScanService(dir.toString(), "", "", 5000, 3, true, 32);
        try {
            Job job = new Job(); job.id = "broken"; job.products = null;
            var save = ProductScanService.class.getDeclaredMethod("save", Job.class);
            save.setAccessible(true);
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> save.invoke(service, job));
            assertInstanceOf(com.ruoyi.common.exception.ServiceException.class, failure.getCause());
            assertInstanceOf(NullPointerException.class, failure.getCause().getCause());
        } finally { service.close(); }
    }
    @Test void workerSupportsHttpHttpsAndNormalizesEndpointPath() {
        assertEquals("http://8.130.138.170/prod-api/product/scan-worker/claim",
                ScanWorkerMain.serverUri("http://8.130.138.170//prod-api/product/scan-worker/").resolve("claim").toString());
        assertEquals("https://example.com:8443/prod-api/product/scan-worker/",
                ScanWorkerMain.serverUri(" https://example.com:8443/prod-api/product/scan-worker ").toString());
        assertEquals("http://127.0.0.1:8080/", ScanWorkerMain.serverUri("HTTP://127.0.0.1:8080").toString());
        for (String invalid : List.of("", "ftp://example.com/", "http://user:secret@example.com/", "https://example.com/?token=x", "https://example.com/#x"))
            assertThrows(IllegalArgumentException.class, () -> ScanWorkerMain.serverUri(invalid));
    }
    @Test void plainJarSelectsRoleAndFailsClosedOnBadConfiguration() throws Exception {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "server:\n  port: 8080\n");
        String location = "--spring.config.location=" + config.toUri();
        assertFalse(ScanWorkerMain.isWorker(ScanWorkerMain.loadConfiguration(location)));
        Files.writeString(config, "app:\n  role: worker\nworker:\n  concurrency: 4\n  token: ${test.secret}\n");
        var environment = ScanWorkerMain.loadConfiguration(location, "--test.secret=example-secret");
        assertTrue(ScanWorkerMain.isWorker(environment));
        assertEquals("4", environment.getProperty("worker.concurrency"));
        assertEquals("example-secret", environment.getProperty("worker.token"));
        assertFalse(ScanWorkerMain.isWorker(ScanWorkerMain.loadConfiguration(location, "--app.role=server")));
        Files.writeString(config, "app:\n  role: workre\n");
        assertThrows(IllegalArgumentException.class, () -> ScanWorkerMain.isWorker(ScanWorkerMain.loadConfiguration(location)));
    }

    @Test void fullFlowChecksAllImportedTitlesBeforeAnyFetch() {
        Product p = new Product(); p.itemId="123"; Request rules = new Request(); rules.titleWords="违规";
        AtomicInteger calls=new AtomicInteger();
        boolean ready=ProductDetectionEngine.prepare(p,rules,List.of(),List.of("正常标题","含违规词的标题"),false,false,
                ()->{calls.incrementAndGet();throw new AssertionError("Title hit must bypass provider");},p,()->{},()->{});
        assertTrue(ready);assertEquals(0,calls.get());assertEquals("MATCHED",p.verdict);
        assertEquals("含违规词的标题",p.title);assertFalse(p.providerFetched);assertTrue(p.importedTitleChecked);
    }
    @Test void fullFlowFetchesAfterTitlePassAndRechecksProviderTitleOnlyWithoutImport() {
        Request rules=new Request();rules.titleWords="违规";
        var info=new com.ruoyi.system.utils.taobao.TaobaoProductInfo("违规接口标题",List.of("https://img.alicdn.com/a.jpg"),"12",List.of(),"分类","店铺","10","5");
        AtomicInteger calls=new AtomicInteger();
        Product p=new Product();p.itemId="123";
        assertTrue(ProductDetectionEngine.prepare(p,rules,List.of(),List.of("正常导入标题"),false,false,
                ()->{calls.incrementAndGet();return info;},p,()->{},()->{}));
        assertTrue(p.titleHits.isEmpty());assertEquals("正常导入标题",p.title);assertEquals(1,p.pictures.size());assertTrue(p.providerFetched);
        Product noImport=new Product();noImport.itemId="456";
        ProductDetectionEngine.prepare(noImport,rules,List.of(),List.of(),false,false,()->info,noImport,()->{},()->{});
        assertEquals(List.of("违规"),noImport.titleHits);assertEquals("12",noImport.price);
        assertEquals(1,calls.get());
    }
    @Test void fullFlowRecheckClearsOldDecisionAndQuotaPreventsImages() {
        Request rules=new Request();rules.titleWords="新词";
        Product p=product();p.verdict="MATCHED";p.titleHits=List.of("旧词");p.review="TRUSTED";
        AtomicBoolean quota=new AtomicBoolean();
        assertFalse(ProductDetectionEngine.prepare(p,rules,List.of(),List.of("正常标题"),true,false,()->null,p,()->{},()->quota.set(true)));
        assertTrue(quota.get());assertTrue(p.pictures.isEmpty());assertEquals("NONE",p.review);
        assertEquals("DAILY_LIMIT_EXCEEDED",p.error);assertFalse(p.providerFetched);
    }
    @Test void providerProxyIsLazyAndConcurrentRetriesShareOneFetch() throws Exception {
        ScanWorkerBroker broker=new ScanWorkerBroker(true,TOKEN);AtomicInteger calls=new AtomicInteger();
        ExecutorService exec=Executors.newFixedThreadPool(4);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Product p=new Product();p.itemId="123";
            Future<?> job=exec.submit(()->{
                try {broker.execute(p,new Request(),List.of(),List.of(),false,false,()->{
                    calls.incrementAndGet();entered.countDown();
                    try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
                    return new com.ruoyi.system.utils.taobao.TaobaoProductInfo("title",List.of(),"1",List.of(),"","","","");
                },dir,()->false,result->{});}catch(Exception e){throw new RuntimeException(e);}
            });
            Input input=claim(broker,"desktop-01");assertEquals(0,calls.get());
            Message message=message(input,"desktop-01");
            Future<FetchReply> first=exec.submit(()->broker.fetch(message));assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<FetchReply> second=exec.submit(()->broker.fetch(message));release.countDown();
            assertEquals("title",first.get(5,TimeUnit.SECONDS).title);assertEquals("title",second.get(5,TimeUnit.SECONDS).title);
            assertEquals("title",broker.fetch(message).title);assertEquals(1,calls.get());
            message.product=input.product;message.product.state="DONE";message.product.verdict="MATCHED";message.product.titleHits=List.of("title");
            broker.complete(message);job.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();exec.shutdownNow();}
    }
    @Test void automaticWorkerIdsPersistAndDifferAcrossMachines() throws Exception {
        Path first=dir.resolve("a"),second=dir.resolve("b");Files.createDirectories(first);Files.createDirectories(second);
        String id=ScanWorkerMain.loadWorkerId(first,"auto");
        assertEquals(id,ScanWorkerMain.loadWorkerId(first,"auto"));
        assertNotEquals(id,ScanWorkerMain.loadWorkerId(second,"auto"));
        assertEquals("manual-id",ScanWorkerMain.loadWorkerId(second,"manual-id"));
    }
    @Test void duplicateLiveIdentityIsRejectedAndOfflineSessionCanReconnect() {
        AtomicLong now=new AtomicLong(System.currentTimeMillis());ScanWorkerBroker broker=new ScanWorkerBroker(true,TOKEN,now::get);
        Message original=message(null,"desktop-01");broker.claim(original);
        Message duplicate=message(null,"desktop-01");duplicate.session="different-process";
        assertThrows(ScanWorkerBroker.DuplicateWorker.class,()->broker.claim(duplicate));
        assertEquals(1,broker.workers().size());assertTrue(broker.workers().get(0).online());
        now.addAndGet(45001);assertFalse(broker.workers().get(0).online());
        assertNull(broker.claim(duplicate));assertTrue(broker.workers().get(0).online());
    }
    @Test void oneWorkersCapacityDoesNotLimitOtherMachines() throws Exception {
        ScanWorkerBroker broker=new ScanWorkerBroker(true,TOKEN);ExecutorService exec=Executors.newFixedThreadPool(2);
        List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<2;i++)jobs.add(exec.submit(()->{
                try{broker.execute(product(),new Request(),List.of(),dir,()->false,p->{});}catch(Exception e){throw new RuntimeException(e);}
            }));
            Message a=message(null,"machine-a");a.capacity=1;
            Input first=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(first==null&&System.nanoTime()<deadline){first=broker.claim(a);if(first==null)Thread.sleep(10);}
            assertNotNull(first);assertNull(broker.claim(a));
            Input second=claim(broker,"machine-b");assertNotEquals(first.taskId,second.taskId);
            for(Input in:List.of(first,second)) {
                Message done=message(in,in==first?"machine-a":"machine-b");done.product=in.product;
                done.product.titleHits=List.of("hit");done.product.state="DONE";done.product.verdict="MATCHED";broker.complete(done);
            }
            for(Future<?> job:jobs)job.get(5,TimeUnit.SECONDS);
        }finally{exec.shutdownNow();}
    }
    @Test void slowResultCommitDoesNotBlockOtherWorkersClaimOrHeartbeat() throws Exception {
        ScanWorkerBroker broker=new ScanWorkerBroker(true,TOKEN);ExecutorService exec=Executors.newFixedThreadPool(4);
        CountDownLatch saving=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> slowJob=exec.submit(()->{
                try{broker.execute(product(),new Request(),List.of(),dir,()->false,p->{
                    saving.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
                });}catch(Exception e){throw new RuntimeException(e);}
            });
            Input slow=claim(broker,"slow");Message result=message(slow,"slow");result.product=slow.product;
            result.product.titleHits=List.of("hit");result.product.state="DONE";result.product.verdict="MATCHED";
            Future<?> commit=exec.submit(()->broker.complete(result));assertTrue(saving.await(5,TimeUnit.SECONDS));
            Future<?> fastJob=exec.submit(()->{
                try{broker.execute(product(),new Request(),List.of(),dir,()->false,p->{});}catch(Exception e){throw new RuntimeException(e);}
            });
            Future<Input> fastClaim=exec.submit(()->claim(broker,"fast"));Input fast=fastClaim.get(1,TimeUnit.SECONDS);
            assertTrue(broker.heartbeat(message(fast,"fast")).accepted);
            release.countDown();commit.get(5,TimeUnit.SECONDS);slowJob.get(5,TimeUnit.SECONDS);
            Message done=message(fast,"fast");done.product=fast.product;done.product.titleHits=List.of("hit");
            done.product.state="DONE";done.product.verdict="MATCHED";broker.complete(done);fastJob.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();exec.shutdownNow();}
    }
    @Test void remoteDispatchCeilingCanExceedEightWithoutChangingLocalDefault() {
        ProductScanService cloud=new ProductScanService(dir.resolve("remote").toString(),"","",5000,3,true,32);
        ProductScanService local=new ProductScanService(dir.resolve("local").toString(),"","",5000,3,false,32);
        try{assertEquals(32,cloud.executionCapacity());assertEquals(3,local.executionCapacity());}
        finally{cloud.close();local.close();}
    }
    @Test void providerAdmissionLimitIsSharedAcrossMachines() throws Exception {
        ScanWorkerBroker broker=new ScanWorkerBroker(true,TOKEN,System::currentTimeMillis,1);
        ExecutorService exec=Executors.newFixedThreadPool(4);AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<2;i++)jobs.add(exec.submit(()->{
                try{broker.execute(product(),new Request(),List.of(),List.of(),false,false,()->{
                    peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
                    try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
                    finally{active.decrementAndGet();}
                    return new com.ruoyi.system.utils.taobao.TaobaoProductInfo("title",List.of(),"1",List.of(),"","","","");
                },dir,()->false,p->{});}catch(Exception e){throw new RuntimeException(e);}
            }));
            Input a=claim(broker,"a"),b=claim(broker,"b");
            Future<?> fa=exec.submit(()->{try{return broker.fetch(message(a,"a"));}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> fb=exec.submit(()->{try{return broker.fetch(message(b,"b"));}catch(Exception e){throw new RuntimeException(e);}});
            release.countDown();fa.get(5,TimeUnit.SECONDS);fb.get(5,TimeUnit.SECONDS);assertEquals(1,peak.get());
            for(Input in:List.of(a,b)){
                Message done=message(in,in==a?"a":"b");done.product=in.product;done.product.titleHits=List.of("hit");
                done.product.state="DONE";done.product.verdict="MATCHED";broker.complete(done);
            }
            for(Future<?> job:jobs)job.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();exec.shutdownNow();}
    }
    @Test void realBundledOcrAndWireSerialization() throws Exception {
        var image = new java.awt.image.BufferedImage(640,180,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0,0,640,180);
        g.setColor(java.awt.Color.BLACK);g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,52));g.drawString("TEST 123456",30,110);g.dispose();
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        EmbeddedOcr.configureWorker(1,1);
        var inspection=EmbeddedOcr.inspectPooled(bytes.toByteArray());
        assertTrue(inspection.ocr().lines.stream().anyMatch(l->l.text.contains("123456")));
        Message m=new Message();m.artifact=inspection.preview();
        assertArrayEquals(m.artifact,JSON.parseObject(JSON.toJSONBytes(m),Message.class).artifact);
    }
}
