package com.ruoyi.system.service.product;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.utils.taobao.TaobaoProductInfo;

/** 商品信息获取、图片下载与内置 OCR。 */
public class ScanGateway
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScanGateway.class);
    private final String key, secret;

    public ScanGateway(String key, String secret) { this.key = key; this.secret = secret; }

    /** 创建任务前校验凭据并加载模型，避免模型未就绪时消耗商品查询次数。 */
    public void checkReady()
    {
        if (key == null || key.isBlank() || secret == null || secret.isBlank())
            throw new ServiceException("暂时无法获取商品信息");
        try { EmbeddedOcr.get(); }
        catch (Exception | LinkageError e)
        {
            log.error("Embedded OCR initialization failed", e);
            throw new ServiceException("图片文字识别初始化失败，请联系管理员");
        }
    }

    public TaobaoProductInfo fetch(String id, String platform)
    {
        return new com.ruoyi.system.utils.taobao.OneBoundProductClient(key, secret).getProduct(id, platform);
    }

    /** 下载商品图片并在进程内识别，同时返回原图坐标和 JPEG 预览。 */
    public Inspection inspect(String url)
    {
        try
        {
            URI uri = URI.create(url);
            validateImageUri(uri);
            byte[] image = download(uri, 20 * 1024 * 1024);
            return EmbeddedOcr.get().inspect(image);
        }
        catch (Exception | LinkageError e)
        {
            log.warn("Image download or embedded OCR failed", e);
            throw new ServiceException("图片下载或文字识别失败，未计为通过");
        }
    }

    /** 仅允许受支持的 HTTPS 图片域名和公网地址，防止借图片链接访问内网。 */
    private static void validateImageUri(URI uri) throws Exception
    {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("img.alicdn.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".alicdn.com"))
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("非受支持的商品图片地址");
        for (InetAddress address : InetAddress.getAllByName(host))
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new IllegalArgumentException();
    }

    private static byte[] download(URI uri, int limit) throws Exception
    {
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        try
        {
            if (c.getResponseCode() != 200) throw new IllegalStateException();
            try (InputStream in = c.getInputStream())
            {
                byte[] bytes = in.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IllegalStateException();
                return bytes;
            }
        }
        finally { c.disconnect(); }
    }

    public record Inspection(ScanModels.Ocr ocr, byte[] preview, int qrCodes) { }
}
