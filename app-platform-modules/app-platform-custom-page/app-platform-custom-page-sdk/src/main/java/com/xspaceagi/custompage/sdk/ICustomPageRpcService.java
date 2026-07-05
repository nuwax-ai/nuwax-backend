package com.xspaceagi.custompage.sdk;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.custompage.sdk.dto.CustomPageQueryReq;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;

import java.util.List;

/**
 * 用户页面RPC服务接口
 */
public interface ICustomPageRpcService {

    /**
     * 创建项目
     */
    String create(Long userId, Long spaceId, String name);

    /**
     * 查询项目列表
     */
    List<CustomPageDto> list(CustomPageQueryReq req);

    /**
     * 批量通过agentId查询项目列表
     */
    List<CustomPageDto> listByAgentIds(List<Long> agentIds);

    /**
     * 分页查询项目
     */
    SuperPage<CustomPageDto> pageQuery(PageQueryVo<CustomPageQueryReq> pageQueryVo);

    /**
     * 查询项目详情
     */
    CustomPageDto queryDetail(Long projectId);

    /**
     * 查询项目详情，包含版本信息
     */
    CustomPageDto queryDetailWithVersion(Long projectId);

    /**
     * 根据agentId查询项目详情
     */
    CustomPageDto queryDetailByAgentId(Long agentId);

    /**
     * 统计网页应用总数
     *
     * @return 网页应用总数
     */
    Long countTotalPages();

    /**
     * 管理端查询网页应用列表
     *
     * @param pageNo     页码
     * @param pageSize   每页大小
     * @param name       名称模糊搜索
     * @param creatorIds 创建人ID列表
     * @param spaceId    空间ID
     * @return 网页应用分页数据
     */
    IPage<CustomPageDto> queryListForManage(Integer pageNo, Integer pageSize, String name, java.util.List<Long> creatorIds,
                                            Long spaceId, List<Long> devAgentIds);

    /**
     * 管理端删除网页应用
     *
     * @param id 项目ID
     */
    void deleteForManage(Long id);

    /**
     * 管理端根据ids批量查询网页应用列表
     */
    List<CustomPageDto> listByIds(List<Long> pageIds, List<Long> agentIds);

    void bindDataSource(Long userId, Long projectId, String type, Long dataSourceId);
}