package com.ruoyi.system.service.impl;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.domain.ProductWhitelist;
import com.ruoyi.system.mapper.ProductWhitelistMapper;
import com.ruoyi.system.service.IProductWhitelistService;

/**
 * 白名单库Service业务层处理
 *
 * @author product-filter
 * @date 2026-09-24
 */
@Service
public class ProductWhitelistServiceImpl implements IProductWhitelistService
{
    @Autowired
    private ProductWhitelistMapper productWhitelistMapper;

    /**
     * 查询白名单库列表。
     *
     * @param productWhitelist 白名单查询条件
     * @return 白名单集合
     */
    @Override
    public List<ProductWhitelist> selectProductWhitelistList(ProductWhitelist productWhitelist)
    {
        return productWhitelistMapper.selectProductWhitelistList(productWhitelist);
    }

    /**
     * 查询白名单详细信息。
     *
     * @param id 白名单主键
     * @return 白名单
     */
    @Override
    public ProductWhitelist selectProductWhitelistById(Long id)
    {
        ProductWhitelist item = productWhitelistMapper.selectProductWhitelistById(id);
        if (item == null) throw new ServiceException("白名单不存在或已删除");
        return item;
    }

    /**
     * 新增白名单；重复时返回0及已有主键，由页面确认后单独调用追加接口。
     *
     * @param productWhitelist 白名单
     * @return 新增行数，0表示需要确认合并
     */
    @Override
    public int insertProductWhitelist(ProductWhitelist productWhitelist)
    {
        String word = productWhitelist.getFilterWord();
        if (word == null || word.trim().isEmpty() || word.trim().length() > 100 || word.contains("\n") || word.contains("\r"))
            throw new ServiceException("过滤词不能为空，最多100个字符，且只能填写一个过滤词");
        if (!List.of("UNIT", "WORD").contains(productWhitelist.getMatchType() == null ? "" : productWhitelist.getMatchType()))
            throw new ServiceException("请选择计量单位或词汇");
        productWhitelist.setId(null);
        productWhitelist.setFilterWord(word.trim());
        productWhitelist.setWordKey(normalize(word.trim()));
        if (productWhitelist.getWordKey().length() > 200) throw new ServiceException("过滤词过长");
        setContent(productWhitelist, productWhitelist.getMatchContent());
        productWhitelist.setStatus("0");
        productWhitelist.setCreateBy(SecurityUtils.getUsername());
        productWhitelist.setCreateTime(DateUtils.getNowDate());
        ProductWhitelist existing = productWhitelistMapper.selectProductWhitelistByKey(productWhitelist);
        if (existing != null) { productWhitelist.setId(existing.getId()); return 0; }
        try { return productWhitelistMapper.insertProductWhitelist(productWhitelist); }
        catch (DuplicateKeyException e)
        {
            // 数据库唯一键兜底处理并发新增，仍需用户确认后才合并内容。
            existing = productWhitelistMapper.selectProductWhitelistByKey(productWhitelist);
            if (existing == null) throw e;
            productWhitelist.setId(existing.getId());
            return 0;
        }
    }

    /**
     * 修改匹配内容；过滤词和匹配方式不允许变更。
     *
     * @param productWhitelist 白名单
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateProductWhitelist(ProductWhitelist productWhitelist)
    {
        ProductWhitelist item = locked(productWhitelist.getId());
        setContent(item, productWhitelist.getMatchContent());
        item.setUpdateBy(SecurityUtils.getUsername());
        item.setUpdateTime(DateUtils.getNowDate());
        return productWhitelistMapper.updateProductWhitelist(item);
    }

    /**
     * 追加匹配内容；锁定记录后合并，避免并发追加丢失内容。
     *
     * @param productWhitelist 包含主键及待追加内容
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int appendProductWhitelist(ProductWhitelist productWhitelist)
    {
        ProductWhitelist item = locked(productWhitelist.getId());
        if (productWhitelist.getMatchContent() == null || productWhitelist.getMatchContent().isBlank())
            throw new ServiceException("请填写需要添加的白名单匹配内容");
        setContent(item, item.getMatchContent() + "\n" + productWhitelist.getMatchContent());
        item.setUpdateBy(SecurityUtils.getUsername());
        item.setUpdateTime(DateUtils.getNowDate());
        return productWhitelistMapper.updateProductWhitelist(item);
    }

    /**
     * 修改启用状态，不修改原匹配内容。
     *
     * @param productWhitelist 包含主键及状态
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeProductWhitelistStatus(ProductWhitelist productWhitelist)
    {
        if (!"0".equals(productWhitelist.getStatus()) && !"1".equals(productWhitelist.getStatus()))
            throw new ServiceException("白名单状态不正确");
        ProductWhitelist item = locked(productWhitelist.getId());
        item.setStatus(productWhitelist.getStatus());
        item.setUpdateBy(SecurityUtils.getUsername());
        item.setUpdateTime(DateUtils.getNowDate());
        return productWhitelistMapper.updateProductWhitelist(item);
    }

    /**
     * 删除空白名单；已配置匹配内容时仅允许禁用。
     *
     * @param id 白名单主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteProductWhitelistById(Long id)
    {
        ProductWhitelist item = locked(id);
        if (item.getMatchContent() != null && !item.getMatchContent().isBlank())
            throw new ServiceException("该过滤词已配置白名单匹配内容，不能删除，请使用禁用功能");
        return productWhitelistMapper.deleteProductWhitelistById(id);
    }

    /** 获取并锁定记录，保证追加、修改、状态切换及删除不会互相覆盖。 */
    private ProductWhitelist locked(Long id)
    {
        if (id == null) throw new ServiceException("请选择白名单");
        ProductWhitelist item = productWhitelistMapper.selectProductWhitelistForUpdate(id);
        if (item == null) throw new ServiceException("白名单不存在或已删除");
        return item;
    }

    /** 按换行分隔，去除空行和重复项；保留第一次输入的大小写及顺序。 */
    private void setContent(ProductWhitelist item, String content)
    {
        if (content == null) throw new ServiceException("缺少白名单匹配内容");
        if (content.length() > 2_000_000) throw new ServiceException("白名单匹配内容过长");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String line : content.split("\\R"))
        {
            String value = line.trim();
            if (value.isEmpty()) continue;
            if (value.length() > 200) throw new ServiceException("每项匹配内容最多200个字符");
            values.putIfAbsent(normalize(value), value);
            if (values.size() > 10000) throw new ServiceException("每条白名单最多配置10000项匹配内容");
        }
        item.setMatchContent(String.join("\n", values.values()));
        item.setMatchCount(values.size());
    }

    /** 与过滤词匹配保持一致，使用全半角归一化及不区分大小写的查重键。 */
    private String normalize(String value)
    {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
