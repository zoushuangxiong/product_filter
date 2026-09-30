package com.ruoyi.system.service.product;

import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.ruoyi.system.service.product.ScanModels.*;
import static com.ruoyi.system.service.product.ScanWorkerProtocol.*;

/** Run after package: real executable JAR -> HTTP -> real cloud broker. No paid APIs or external network. */
public final class ScanWorkerJarIT {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath(), dir = Files.createTempDirectory("scan-worker-jar-it-");
        String token = "integration-secret-0123456789abcdef";
        ScanWorkerBroker broker = new ScanWorkerBroker(true,token);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService httpThreads = Executors.newCachedThreadPool(), jobs = Executors.newFixedThreadPool(12);
        AtomicBoolean loseAck = new AtomicBoolean(true), loseFetchAck = new AtomicBoolean(true);
        AtomicInteger fetchCalls = new AtomicInteger(), imageUploads = new AtomicInteger();
        AtomicInteger claims = new AtomicInteger(), commits = new AtomicInteger(), hearts = new AtomicInteger();
        Set<String> workerIds = ConcurrentHashMap.newKeySet(), claimedWorkers = ConcurrentHashMap.newKeySet();
        CountDownLatch claimBarrier = new CountDownLatch(3);
        server.setExecutor(httpThreads);
        server.createContext("/product/scan-worker/", exchange -> {
            int status=200; Object response=null;
            try {
                if (!broker.authenticate(exchange.getRequestHeaders().getFirst("X-Scan-Worker-Token"))) {status=401;}
                else {
                    Message m=JSON.parseObject(exchange.getRequestBody().readAllBytes(),Message.class);workerIds.add(m.workerId);
                    String op=exchange.getRequestURI().getPath().substring("/product/scan-worker/".length());
                    response=switch(op) {
                        case "claim" -> {
                            Input i=broker.claim(m);
                            if(i!=null) {
                                claims.incrementAndGet();
                                if(claimedWorkers.add(m.workerId))claimBarrier.countDown();
                                if(!claimBarrier.await(10,TimeUnit.SECONDS))throw new AssertionError("Not all workers received tasks");
                            }
                            yield i;
                        }
                        case "fetch" -> broker.fetch(m);
                        case "heartbeat" -> { hearts.incrementAndGet(); yield broker.heartbeat(m); }
                        case "artifact" -> { imageUploads.incrementAndGet(); throw new IllegalStateException("Unexpected image upload"); }
                        case "progress" -> broker.progress(m);
                        case "complete" -> broker.complete(m);
                        default -> throw new IllegalArgumentException();
                    };
                    // Simulate network ACK loss AFTER the cloud has durably accepted a result.
                    if(op.equals("fetch") && loseFetchAck.getAndSet(false)) status=503;
                    else if(op.equals("complete") && loseAck.getAndSet(false)) status=503;
                    else if(response==null)status=204;
                }
            } catch(ScanWorkerBroker.DuplicateWorker e){status=423;}
            catch(ScanWorkerBroker.Conflict e){status=409;}
            catch(InterruptedException e){Thread.currentThread().interrupt();status=503;}
            catch(Exception e){e.printStackTrace();status=500;}
            try {
                byte[] bytes=response==null?new byte[0]:JSON.toJSONBytes(response);
                exchange.sendResponseHeaders(status,status==204?-1:bytes.length);
                if(status!=204)exchange.getResponseBody().write(bytes);
            } finally {exchange.close();}
        });
        server.start(); List<Process> processes = new ArrayList<>();
        try {
            for(int machine=0;machine<3;machine++) {
                Path machineDir=dir.resolve("machine-"+machine);Files.createDirectories(machineDir);
                Path config=machineDir.resolve("application.yml");
                Files.writeString(config,"app:\n  role: worker\nworker:\n  server-url: http://127.0.0.1:"+server.getAddress().getPort()+"/product/scan-worker/\n  token: "+token+"\n  id: auto\n  concurrency: 4\n  ocr-threads: 1\n  data-dir: '"+machineDir.resolve("data")+"'\n");
                ProcessBuilder pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx1g","-jar",jar.toString());
                pb.directory(machineDir.toFile());
                pb.environment().put("SCAN_WORKER_TOKEN",token);pb.redirectErrorStream(true).redirectOutput(machineDir.resolve("worker.log").toFile());processes.add(pb.start());
            }
            long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(System.nanoTime()<readyDeadline && broker.workers().size()<3) {
                if(processes.stream().anyMatch(p->!p.isAlive()))throw new AssertionError("Worker exited during startup");
                Thread.sleep(100);
            }
            if(broker.workers().size()!=3)throw new AssertionError("Workers did not connect");
            List<Future<?>> results=new ArrayList<>();
            for(int i=0;i<24;i++) {
                int n=i;
                results.add(jobs.submit(()-> {
                    Product p=new Product();p.itemId=""+(n+1);
                    // 带有图片识别证据但云端无 JPG，也必须能提交；不得调用图片上传接口。
                    Picture picture = new Picture(); picture.index = 1; picture.kind = "MAIN";
                    picture.url = "https://img.alicdn.com/test.jpg"; picture.state = "DONE";
                    picture.previewKey = "a".repeat(64); picture.ocr = new Ocr(); p.pictures.add(picture);
                    Request rules=new Request();rules.titleWords="demo";
                    try {broker.execute(p,rules,List.of(),n%2==0?List.of("demo imported title"):List.of(),false,false,
                        () -> {
                            fetchCalls.incrementAndGet();
                            return new com.ruoyi.system.utils.taobao.TaobaoProductInfo("demo provider title",List.of(),"1",List.of(),"","","","");
                        },()->false,result->{
                        if(!"DONE".equals(result.state))return;
                        if(!"MATCHED".equals(result.verdict))throw new AssertionError("Wrong result");
                        try {Files.writeString(dir.resolve("result-"+n+".json"),JSON.toJSONString(result));}
                        catch(Exception e){throw new RuntimeException(e);}commits.incrementAndGet();
                    });}catch(Exception e){throw new RuntimeException(e);}
                }));
            }
            for(Future<?> result:results)result.get(60,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            boolean empty=false;
            while(System.nanoTime()<deadline) {
                empty=true;
                for(int machine=0;machine<3;machine++) {
                    try(var files=Files.list(dir.resolve("machine-"+machine+"/data/outbox"))){empty &= files.findAny().isEmpty();}
                }
                if(empty)break;Thread.sleep(100);
            }
            if(!empty||claims.get()!=24||commits.get()!=24||hearts.get()==0||workerIds.size()!=3||claimedWorkers.size()!=3||fetchCalls.get()!=12)throw new AssertionError("Lifecycle counters invalid");
            if(imageUploads.get()!=0)throw new AssertionError("Images were uploaded");
            System.out.println("PASS no-upload results with preview hashes, zero image uploads; real JAR worker: 3 worker processes, 12 slots, 24 tasks, heartbeats, results, lost-ACK retry, no duplicate commits, worker title checks, 12 title hits bypass fetch, 12 worker-driven fetches with lost fetch ACK, no Spring/DB/Redis startup");
        } catch(Exception|AssertionError e) {
            System.err.println("Test diagnostics: "+dir);
            for(int machine=0;machine<3;machine++) {
                Path log=dir.resolve("machine-"+machine+"/worker.log");
                if(Files.exists(log))System.err.println(Files.readString(log));
            }
            throw e;
        } finally {
            for(Process worker:processes){worker.destroy();if(!worker.waitFor(20,TimeUnit.SECONDS))worker.destroyForcibly();}
            server.stop(0);httpThreads.shutdownNow();jobs.shutdownNow();
        }
    }
}
