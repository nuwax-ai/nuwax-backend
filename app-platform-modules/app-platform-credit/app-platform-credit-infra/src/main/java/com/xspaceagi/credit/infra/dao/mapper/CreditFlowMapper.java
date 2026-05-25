package com.xspaceagi.credit.infra.dao.mapper;

import com.xspaceagi.credit.infra.dao.entity.CreditFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CreditFlowMapper {

    int insert(CreditFlow creditFlow);

    int batchInsert(@Param("list") List<CreditFlow> list);

    /**
     * 获取用户指定类型的前N条流水记录（按ID降序）
     */
    List<CreditFlow> selectByUserIdAndType(@Param("userId") Long userId,
                                          @Param("creditType") Integer creditType,
                                          @Param("limit") Integer limit);

    /**
     * 获取用户指定类型且ID小于指定值的流水记录（用于分页）
     */
    List<CreditFlow> selectByUserIdAndTypeWithOffset(@Param("userId") Long userId,
                                                    @Param("creditType") Integer creditType,
                                                    @Param("lastId") Long lastId,
                                                    @Param("limit") Integer limit);

    CreditFlow selectByBizNo(@Param("bizNo") String bizNo, @Param("operationType") Integer operationType);

    int countByUserId(@Param("userId") Long userId);
}
