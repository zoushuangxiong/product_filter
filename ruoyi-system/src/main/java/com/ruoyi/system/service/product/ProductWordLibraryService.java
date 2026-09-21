package com.ruoyi.system.service.product;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.ProductWordLibrary;
import com.ruoyi.system.mapper.ProductWordLibraryMapper;

/** 词库持久化及规则校验，与实际检测使用相同的拆词规则。 */
@Service
public class ProductWordLibraryService
{
    private final ProductWordLibraryMapper mapper;
    public ProductWordLibraryService(ProductWordLibraryMapper mapper) { this.mapper = mapper; }

    public List<ProductWordLibrary> list(Long ownerId, String name) { return mapper.selectList(ownerId, name); }

    public ProductWordLibrary get(Long ownerId, Long id)
    {
        ProductWordLibrary library = mapper.selectById(ownerId, id);
        if (library == null) throw new ServiceException("词库不存在或已删除");
        return library;
    }

    public int save(Long ownerId, String username, ProductWordLibrary library, boolean create)
    {
        if (library == null || library.getName() == null || library.getName().isBlank())
            throw new ServiceException("请填写词库名称");
        library.setName(library.getName().trim());
        if (library.getName().length() > 50) throw new ServiceException("词库名称不能超过 50 字符");
        List<String> title = ScanRules.words(library.getTitleWords());
        List<String> images = ScanRules.words(library.getImageWords() == null ? "" : library.getImageWords());
        if (title.isEmpty()) throw new ServiceException("请填写标题过滤词");
        if (images.isEmpty() && !Boolean.TRUE.equals(library.getDetectPhones()))
            throw new ServiceException("请填写图片文字过滤词，或启用手机号检测");
        library.setTitleWords(String.join("\n", title));
        library.setImageWords(String.join("\n", images));
        library.setDetectPhones(Boolean.TRUE.equals(library.getDetectPhones()));
        // 归属和审计字段从登录态获取，不接受前端指定。
        library.setOwnerId(ownerId);
        try
        {
            if (create)
            {
                library.setId(null);
                library.setCreateBy(username);
                return mapper.insert(library);
            }
            get(ownerId, library.getId());
            library.setUpdateBy(username);
            return mapper.update(library);
        }
        catch (DuplicateKeyException e) { throw new ServiceException("已存在同名词库，请更换名称"); }
    }

    public int delete(Long ownerId, Long id)
    {
        get(ownerId, id);
        return mapper.delete(ownerId, id);
    }
}
