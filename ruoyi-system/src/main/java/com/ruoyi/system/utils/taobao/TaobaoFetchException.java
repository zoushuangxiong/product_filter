package com.ruoyi.system.utils.taobao;

/** 商品获取失败。消息不包含 API 密钥、请求 URL 或上游响应正文。 */
public class TaobaoFetchException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public enum Code
    {
        INVALID_ITEM_ID("商品 ID 必须为 1 至 20 位正整数"),
        MISSING_API_CREDENTIALS("商品数据服务尚未配置，请联系管理员"),
        PROVIDER_ACCESS_DENIED("商品数据服务鉴权失败或未开通商品查询权限"),
        PROVIDER_ERROR("商品数据服务返回错误，请检查账户状态和接口权限"),
        RATE_LIMITED("商品接口请求频率受限，请稍后重试"),
        ITEM_UNAVAILABLE("商品不存在、已下架或当前不可访问"),
        INVALID_RESPONSE("商品接口响应缺少预期字段或格式已变化"),
        TIMEOUT("请求商品接口超时"),
        NETWORK_ERROR("连接商品接口失败"),
        HTTP_ERROR("商品接口返回非成功 HTTP 状态"),
        RESPONSE_TOO_LARGE("商品接口响应超过 5 MiB 限制");

        private final String message;

        Code(String message)
        {
            this.message = message;
        }
    }

    private final Code code;

    public TaobaoFetchException(Code code)
    {
        super(code.message);
        this.code = code;
    }

    public Code getCode()
    {
        return code;
    }
}
