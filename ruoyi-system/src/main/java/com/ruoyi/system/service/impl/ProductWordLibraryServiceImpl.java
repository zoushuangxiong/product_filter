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
 * 当前阶段词库作为全局配置使用，暂不按用户做数据隔离；后续统一权限模块接入后再补充。
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
        return productWordLibraryMapper.selectProductWordLibraryList(productWordLibrary);
    }

    /**
     * 通过词库ID查询词库信息
     *
     * @param id 词库ID
     * @return 词库信息
     */
    @Override
    public ProductWordLibrary selectProductWordLibraryById(Long id)
    {
        ProductWordLibrary productWordLibrary = productWordLibraryMapper.selectProductWordLibraryById(id);
        if (productWordLibrary == null)
        {
            throw new ServiceException("词库不存在或已删除");
        }
        return productWordLibrary;
    }

    /**
     * 新增保存过滤词库
     *
     * @param productWordLibrary 词库信息
     * @return 结果
     */
    @Override
    public int insertProductWordLibrary(ProductWordLibrary productWordLibrary)
    {
        validate(productWordLibrary);
        productWordLibrary.setId(null);
        productWordLibrary.setOwnerId(SecurityUtils.getUserId());
        productWordLibrary.setCreateBy(SecurityUtils.getUsername());
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
     * @param productWordLibrary 词库信息
     * @return 结果
     */
    @Override
    public int updateProductWordLibrary(ProductWordLibrary productWordLibrary)
    {
        validate(productWordLibrary);
        selectProductWordLibraryById(productWordLibrary.getId());
        productWordLibrary.setUpdateBy(SecurityUtils.getUsername());
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
     * @param productWordLibrary 词库信息，包含待删除的主键
     * @return 结果
     */
    @Override
    public int deleteProductWordLibraryById(ProductWordLibrary productWordLibrary)
    {
        selectProductWordLibraryById(productWordLibrary.getId());
        return productWordLibraryMapper.deleteProductWordLibraryById(productWordLibrary);
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
        if (images.isEmpty() && !Boolean.TRUE.equals(productWordLibrary.getDetectPhones())
            && !Boolean.TRUE.equals(productWordLibrary.getDetectQrCodes()))
        {
            throw new ServiceException("请填写图片文字过滤词，或启用手机号/二维码检测");
        }
        productWordLibrary.setTitleWords(String.join("\n", title));
        productWordLibrary.setImageWords(String.join("\n", images));
        productWordLibrary.setDetectPhones(Boolean.TRUE.equals(productWordLibrary.getDetectPhones()));
        productWordLibrary.setDetectQrCodes(Boolean.TRUE.equals(productWordLibrary.getDetectQrCodes()));
    }
}
