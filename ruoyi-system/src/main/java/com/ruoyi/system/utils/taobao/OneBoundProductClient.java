package com.ruoyi.system.utils.taobao;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.util.HtmlUtils;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.utils.taobao.TaobaoFetchException.Code;

/** 商品详情接口客户端；一次调用一个商品，不跟随重定向、不记录带密钥的请求 URL。 */
public final class OneBoundProductClient
{
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final String key;
    private final String secret;

    public OneBoundProductClient(String key, String secret)
    {
        if (key == null || key.isBlank() || secret == null || secret.isBlank())
        {
            throw new TaobaoFetchException(Code.MISSING_API_CREDENTIALS);
        }
        this.key = key;
        this.secret = secret;
    }

    /** 按平台调用一次商品详情接口；重试由任务服务统一控制。 */
    public TaobaoProductInfo getProduct(String itemId, String platform)
    {
        if (!java.util.Set.of("taobao", "1688").contains(platform == null ? "" : platform))
            throw new IllegalArgumentException("不支持的商品平台");
        if (itemId == null || !itemId.matches("[1-9][0-9]{0,19}"))
            throw new TaobaoFetchException(Code.INVALID_ITEM_ID);
        URI uri = URI.create("https://api-gw.onebound.cn/" + platform + "/item_get/?key=" + encode(key)
                + "&secret=" + encode(secret) + "&num_iid=" + encode(itemId)
                + "&is_promotion=1&lang=zh-CN&result_type=json");
        Response response;
        try
        {
            response = request(uri);
        }
        catch (SocketTimeoutException e)
        {
            throw new TaobaoFetchException(Code.TIMEOUT);
        }
        catch (IOException e)
        {
            // 不保留异常原因：底层网络异常可能包含带密钥的完整 URL。
            throw new TaobaoFetchException(Code.NETWORK_ERROR);
        }
        if (response.status() == 429)
        {
            throw new TaobaoFetchException(Code.RATE_LIMITED);
        }
        if (response.status() != 200)
        {
            throw new TaobaoFetchException(Code.HTTP_ERROR);
        }
        return parse(itemId, response.body());
    }

    /** 校验业务状态与返回商品 ID，仅提取当前检测和 CSV 导出需要的字段。 */
    private static TaobaoProductInfo parse(String itemId, String body)
    {
        try
        {
            JSONObject root = JSON.parseObject(body);
            if (root == null || root.getString("error_code") == null)
            {
                throw new TaobaoFetchException(Code.INVALID_RESPONSE);
            }
            String code = root.getString("error_code");
            if (!"0000".equals(code))
            {
                throw new TaobaoFetchException(switch (code) {
                    case "2000" -> Code.ITEM_UNAVAILABLE;
                    case "4005", "4006" -> Code.PROVIDER_ACCESS_DENIED;
                    case "4008" -> Code.RATE_LIMITED;
                    default -> Code.PROVIDER_ERROR;
                });
            }
            JSONObject item = root.getJSONObject("item");
            if (item == null || !itemId.equals(item.getString("num_iid"))
                    || !(item.get("title") instanceof String) || item.getString("title").isBlank()
                    || (item.getString("error") != null && !item.getString("error").isBlank()))
            {
                throw new TaobaoFetchException(Code.INVALID_RESPONSE);
            }
            Set<String> main = new LinkedHashSet<>();
            addImage(item.getString("pic_url"), main);
            imageList(item.get("item_imgs"), main);
            if (main.isEmpty())
            {
                throw new TaobaoFetchException(Code.INVALID_RESPONSE);
            }
            // 只解析已知详情字段，避免混入 SKU、推荐商品或评价图片。
            Set<String> details = new LinkedHashSet<>();
            imageList(item.get("desc_img"), details);
            htmlImages(item.get("desc") instanceof String ? item.getString("desc") : null, details);
            List<String> detailImages = List.copyOf(details);
            return new TaobaoProductInfo(item.getString("title"),
                    List.copyOf(main), item.getString("price"), detailImages,
                    item.getString("cat_name"), item.getJSONObject("seller_info") == null ? null
                            : item.getJSONObject("seller_info").getString("zhuy"),
                    item.getString("sales"), item.getString("comment_count"));
        }
        catch (TaobaoFetchException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            throw new TaobaoFetchException(Code.INVALID_RESPONSE);
        }
    }

    private static void imageList(Object value, Set<String> images)
    {
        if (value instanceof JSONArray)
        {
            for (Object entry : (JSONArray) value)
            {
                if (entry instanceof String)
                {
                    addImage((String) entry, images);
                }
                else if (entry instanceof JSONObject && ((JSONObject) entry).get("url") instanceof String)
                {
                    addImage(((JSONObject) entry).getString("url"), images);
                }
            }
        }
    }

    private static void htmlImages(String html, Set<String> images)
    {
        if (html == null)
        {
            return;
        }
        Document document = Jsoup.parseBodyFragment(html);
        document.select("script, style").remove();
        for (Element image : document.select("img"))
        {
            if (hidden(image))
            {
                continue;
            }
            String source = image.attr("src");
            for (String attribute : List.of("data-ks-lazyload", "data-src"))
            {
                if (normalizeUrl(image.attr(attribute)) != null)
                {
                    source = image.attr(attribute);
                    break;
                }
            }
            // 懒加载属性优先，避免把透明占位图当成详情图。
            addImage(source, images);
        }
    }

    private static boolean hidden(Element element)
    {
        for (Element current = element; current != null; current = current.parent())
        {
            String style = current.attr("style").toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
            if (current.hasAttr("hidden") || style.matches(".*(?:^|;)display:none(?:!important)?(?:;|$).*")
                    || style.matches(".*(?:^|;)visibility:hidden(?:!important)?(?:;|$).*"))
            {
                return true;
            }
        }
        return false;
    }

    private static void addImage(String value, Set<String> images)
    {
        String url = normalizeUrl(value);
        if (url != null)
        {
            images.add(url);
        }
    }

    private static String normalizeUrl(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        String url = HtmlUtils.htmlUnescape(value.trim());
        if (url.startsWith("//"))
        {
            url = "https:" + url;
        }
        try
        {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host != null && ((host.equalsIgnoreCase("o0b.cn") || host.equalsIgnoreCase("www.o0b.cn"))
                    && "/i.php".equals(path)
                    || host.equalsIgnoreCase("assets.alicdn.com") && path != null && path.endsWith("/spaceball.gif")))
            {
                return null;
            }
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? url : null;
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Response request(URI uri) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        // 单次调用最多约 11 秒，避免网络异常时一个商品长时间卡住。
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        try
        {
            int status = connection.getResponseCode();
            if (status != 200)
            {
                return new Response(status, "");
            }
            try (InputStream stream = connection.getInputStream())
            {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES)
                {
                    throw new TaobaoFetchException(Code.RESPONSE_TOO_LARGE);
                }
                return new Response(status, new String(bytes, StandardCharsets.UTF_8));
            }
        }
        finally
        {
            connection.disconnect();
        }
    }

    private record Response(int status, String body) { }

}
