-- 商品接口成功结果的持久化缓存；仅创建数据表，不新增或修改任何菜单。
CREATE TABLE IF NOT EXISTS product_info_cache (
    platform varchar(16) NOT NULL COMMENT '商品平台',
    item_id varchar(20) NOT NULL COMMENT '商品ID',
    title text NOT NULL COMMENT '接口返回的原始标题',
    main_images mediumtext NOT NULL COMMENT '全部主图链接JSON数组',
    detail_images mediumtext NOT NULL COMMENT '全部详情图链接JSON数组',
    price_text text COMMENT '价格文本',
    category_name text COMMENT '类目名称',
    shop_url text COMMENT '店铺链接',
    sales text COMMENT '销量文本',
    comment_count text COMMENT '评论数文本',
    fetched_time datetime NOT NULL COMMENT '接口获取时间',
    PRIMARY KEY (platform, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品信息缓存';
