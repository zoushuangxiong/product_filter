package com.ruoyi.web.controller.product;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.system.service.product.ScanWorkerBroker;
import com.ruoyi.system.service.product.ScanWorkerProtocol.Message;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 独立机器凭证鉴权；不使用用户登录 Token，不改变现有商品检测接口权限。 */
@Anonymous
@RestController
@RequestMapping("/product/scan-worker")
@ConditionalOnProperty(name = "product.scan.remote.enabled", havingValue = "true")
public class ScanWorkerController {
    private final ScanWorkerBroker broker;
    public ScanWorkerController(ScanWorkerBroker broker) { this.broker = broker; }
    @PostMapping("/{operation}")
    public ResponseEntity<?> exchange(@PathVariable("operation") String operation, HttpServletRequest request) throws Exception {
        if (!broker.authenticate(request.getHeader("X-Scan-Worker-Token"))) return ResponseEntity.status(401).build();
        // Bound body reads after authentication; preview uploads use this same private protocol.
        byte[] body = request.getInputStream().readNBytes(28 * 1024 * 1024 + 1);
        if (body.length > 28 * 1024 * 1024) return ResponseEntity.status(413).build();
        try {
            Message message = JSON.parseObject(body, Message.class);
            Object reply = switch (operation) {
                case "claim" -> broker.claim(message);
                case "fetch" -> broker.fetch(message);
                case "heartbeat" -> broker.heartbeat(message);
                case "progress" -> broker.progress(message);
                case "complete" -> broker.complete(message);
                default -> null;
            };
            if (reply == null) return ResponseEntity.noContent().build();
            return ResponseEntity.ok(reply);
        } catch (ScanWorkerBroker.DuplicateWorker e) { return ResponseEntity.status(423).build(); }
        catch (ScanWorkerBroker.Conflict e) { return ResponseEntity.status(409).build(); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
    }
}
