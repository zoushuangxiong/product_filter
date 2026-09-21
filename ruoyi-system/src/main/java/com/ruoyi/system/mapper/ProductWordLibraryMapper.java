package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.ProductWordLibrary;

/** 所有查询和修改都带用户条件，防止通过猜测词库 ID 访问他人数据。 */
public interface ProductWordLibraryMapper
{
    List<ProductWordLibrary> selectList(@Param("ownerId") Long ownerId, @Param("name") String name);
    ProductWordLibrary selectById(@Param("ownerId") Long ownerId, @Param("id") Long id);
    int insert(ProductWordLibrary library);
    int update(ProductWordLibrary library);
    int delete(@Param("ownerId") Long ownerId, @Param("id") Long id);
}
