package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.ProductWordLibrary;
import com.ruoyi.system.mapper.ProductWordLibraryMapper;
import com.ruoyi.system.service.IProductWordLibraryService;
import com.ruoyi.system.service.product.ScanRules;

/**
 * 过滤词库Service业务层处理
 *
 * 归属用户由登录态传入，所有查询和修改都通过 ownerId 做数据隔离。
 * 保存前复用商品检测规则，确保管理页和检测页的拆词、数量限制一致。
 *
 * @author product-filter
 * @date 2026-09-22
 */
@Service
public class ProductWordLibraryServiceImpl implements IProductWordLibraryService
{
    @Autowired
    private ProductWordLibraryMapper productWordLibraryMapper;

    /**
     * 查询过滤词库集合
     *
     * @param productWordLibrary 过滤词库查询条件
     * @return 词库集合
     */
    @Override
    public List<ProductWordLibrary> selectProductWordLibraryList(ProductWordLibrary productWordLibrary)
    {
        // 所属用户以登录态为准，覆盖请求中的用户ID，防止查询他人词库。
        productWordLibrary.setOwnerId(SecurityUtils.getUserId());
        return productWordLibraryMapper.selectProductWordLibraryList(productWordLibrary);
    }

    /**
     * 通过词库ID查询词库信息
     *
     * @param ownerId 当前登录用户ID
     * @param id 词库ID
     * @return 词库信息
     */
    @Override
    public ProductWordLibrary selectProductWordLibraryById(Long ownerId, Long id)
    {
        ProductWordLibrary productWordLibrary = productWordLibraryMapper.selectProductWordLibraryById(ownerId, id);
        if (productWordLibrary == null)
        {
            throw new ServiceException("词库不存在或已删除");
        }
        return productWordLibrary;
    }

    /**
     * 新增保存过滤词库
     *
     * @param ownerId 当前登录用户ID
     * @param username 当前登录用户名
     * @param productWordLibrary 词库信息
     * @return 结果
     */
    @Override
    public int insertProductWordLibrary(Long ownerId, String username, ProductWordLibrary productWordLibrary)
    {
        validate(productWordLibrary);
        productWordLibrary.setId(null);
        productWordLibrary.setOwnerId(ownerId);
        productWordLibrary.setCreateBy(username);
        try
        {
            return productWordLibraryMapper.insertProductWordLibrary(productWordLibrary);
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("已存在同名词库，请更换名称");
        }
    }

    /**
     * 修改保存过滤词库
     *
     * @param ownerId 当前登录用户ID
     * @param username 当前登录用户名
     * @param productWordLibrary 词库信息
     * @return 结果
     */
    @Override
    public int updateProductWordLibrary(Long ownerId, String username, ProductWordLibrary productWordLibrary)
    {
        validate(productWordLibrary);
        selectProductWordLibraryById(ownerId, productWordLibrary.getId());
        productWordLibrary.setOwnerId(ownerId);
        productWordLibrary.setUpdateBy(username);
        try
        {
            return productWordLibraryMapper.updateProductWordLibrary(productWordLibrary);
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("已存在同名词库，请更换名称");
        }
    }

    /**
     * 删除过滤词库
     *
     * @param ownerId 当前登录用户ID
     * @param id 词库ID
     * @return 结果
     */
    @Override
    public int deleteProductWordLibraryById(Long ownerId, Long id)
    {
        selectProductWordLibraryById(ownerId, id);
        return productWordLibraryMapper.deleteProductWordLibraryById(ownerId, id);
    }

    /**
     * 校验并规范化词库内容
     *
     * @param productWordLibrary 待校验词库
     */
    private static void validate(ProductWordLibrary productWordLibrary)
    {
        if (productWordLibrary == null || productWordLibrary.getName() == null
            || productWordLibrary.getName().isBlank())
        {
            throw new ServiceException("请填写词库名称");
        }
        productWordLibrary.setName(productWordLibrary.getName().trim());
        if (productWordLibrary.getName().length() > 50)
        {
            throw new ServiceException("词库名称不能超过 50 字符");
        }
        List<String> title = ScanRules.words(productWordLibrary.getTitleWords());
        List<String> images = ScanRules.words(productWordLibrary.getImageWords() == null ? "" : productWordLibrary.getImageWords());
        if (title.isEmpty())
        {
            throw new ServiceException("请填写标题过滤词");
        }
        if (images.isEmpty() && !Boolean.TRUE.equals(productWordLibrary.getDetectPhones()))
        {
            throw new ServiceException("请填写图片文字过滤词，或启用手机号检测");
        }
        productWordLibrary.setTitleWords(String.join("\n", title));
        productWordLibrary.setImageWords(String.join("\n", images));
        productWordLibrary.setDetectPhones(Boolean.TRUE.equals(productWordLibrary.getDetectPhones()));
    }
}
