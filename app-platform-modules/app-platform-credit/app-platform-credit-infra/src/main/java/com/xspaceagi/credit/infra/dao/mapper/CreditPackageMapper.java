package com.xspaceagi.credit.infra.dao.mapper;

import com.xspaceagi.credit.infra.dao.entity.CreditPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CreditPackageMapper {

    int insert(CreditPackage creditPackage);

    int updateById(CreditPackage creditPackage);

    int deleteById(@Param("id") Long id);

    CreditPackage selectById(@Param("id") Long id);

    List<CreditPackage> selectList(@Param("status") Integer status);
}
