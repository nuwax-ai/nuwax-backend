package com.xspaceagi.memory.app.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.memory.app.service.MemoryUnitService;
import com.xspaceagi.memory.app.service.MemoryUnitTagService;
import com.xspaceagi.memory.infra.dao.entity.MemoryUnit;
import com.xspaceagi.memory.infra.dao.mapper.MemoryUnitMapper;
import com.xspaceagi.memory.sdk.dto.*;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆单元服务实现
 */
@Slf4j
@Service
public class MemoryUnitServiceImpl extends ServiceImpl<MemoryUnitMapper, MemoryUnit> implements MemoryUnitService {

    @Resource
    private MemoryUnitTagService memoryUnitTagService;

    @Resource
    private MemoryUnitMapper memoryUnitMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryUnitDTO create(MemoryUnitCreateDTO createDTO) {
        log.info("创建记忆单元: {}", createDTO);
        JSONObject contentJson = new JSONObject();
        contentJson.put("keyValues", createDTO.getKeyValues());
        contentJson.put("tags", createDTO.getTags());
        MemoryUnit entity = MemoryUnit.builder()
                .tenantId(createDTO.getTenantId()) // 这里可以根据实际业务逻辑获取租户ID
                .userId(createDTO.getUserId())
                .agentId(createDTO.getAgentId())
                .category(createDTO.getCategory())
                .subCategory(createDTO.getSubCategory())
                .contentJson(contentJson.toJSONString())
                .isSensitive(createDTO.getIsSensitive() != null ? createDTO.getIsSensitive() : false)
                .status("active")
                .build();
        save(entity);
        log.info("记忆单元创建成功: {}", entity.getId());
        if (CollectionUtils.isNotEmpty(createDTO.getTags())) {
            createDTO.getTags().forEach(tag -> {
                MemoryUnitTagCreateDTO memoryUnitTagCreateDTO = new MemoryUnitTagCreateDTO();
                memoryUnitTagCreateDTO.setUserId(createDTO.getUserId());
                memoryUnitTagCreateDTO.setMemoryId(entity.getId());
                memoryUnitTagCreateDTO.setTagName(tag);
                memoryUnitTagService.create(memoryUnitTagCreateDTO);
            });
        }
        return toDTO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryUnitDTO update(MemoryUnitUpdateDTO updateDTO) {
        log.info("更新记忆单元: {}", updateDTO);

        MemoryUnit entity = super.getById(updateDTO.getId());
        if (entity == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.memoryUnitNotFound, updateDTO.getId());
        }

        if (updateDTO.getCategory() != null) {
            entity.setCategory(updateDTO.getCategory());
        }
        if (updateDTO.getSubCategory() != null) {
            entity.setSubCategory(updateDTO.getSubCategory());
        }
        if (updateDTO.getContentJson() != null) {
            entity.setContentJson(updateDTO.getContentJson());
        }
        if (updateDTO.getIsSensitive() != null) {
            entity.setIsSensitive(updateDTO.getIsSensitive());
        }
        if (updateDTO.getStatus() != null) {
            entity.setStatus(updateDTO.getStatus());
        }
        entity.setModified(new Date());
        updateById(entity);
        saveTagRelation(updateDTO.getUserId(), entity.getId(), updateDTO.getTags());

        log.info("记忆单元更新成功: {}", entity.getId());
        return toDTO(entity);
    }

    private void saveTagRelation(Long userId, Long memoryId, List<String> tags) {
        if (CollectionUtils.isNotEmpty(tags)) {
            tags.forEach(tag -> {
                MemoryUnitTagCreateDTO memoryUnitTagCreateDTO = new MemoryUnitTagCreateDTO();
                memoryUnitTagCreateDTO.setUserId(userId);
                memoryUnitTagCreateDTO.setMemoryId(memoryId);
                memoryUnitTagCreateDTO.setTagName(tag);
                try {
                    memoryUnitTagService.create(memoryUnitTagCreateDTO);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    public MemoryUnitDTO getById(Long id) {
        MemoryUnit entity = super.getById(id);
        return entity != null ? toDTO(entity) : null;
    }

    @Override
    public List<MemoryUnitDTO> queryList(MemoryUnitQueryDTO queryDTO) {
        log.info("查询记忆单元列表: {}", queryDTO);
        List<MemoryUnit> entities;
        List<Date> timeRange = null;
        boolean timeRangeQuery = false;
        try {
            timeRange = parseTimeRange(queryDTO);
            timeRangeQuery = timeRange.size() == 2;
        } catch (Exception ignored) {
        }
        LambdaQueryWrapper<MemoryUnit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MemoryUnit::getStatus, "active");
        queryWrapper.eq(MemoryUnit::getTenantId, queryDTO.getTenantId());
        queryWrapper.eq(MemoryUnit::getUserId, queryDTO.getUserId());
        queryWrapper.eq(MemoryUnit::getAgentId, queryDTO.getAgentId());
        queryWrapper.in(CollectionUtils.isNotEmpty(queryDTO.getCategories()), MemoryUnit::getCategory, queryDTO.getCategories());
        queryWrapper.in(CollectionUtils.isNotEmpty(queryDTO.getSubCategories()), MemoryUnit::getSubCategory, queryDTO.getSubCategories());
        queryWrapper.orderByDesc(MemoryUnit::getId);
        if (timeRangeQuery) {
            queryWrapper.between(MemoryUnit::getModified, timeRange.get(0), timeRange.get(1));
        }
        queryWrapper.last("LIMIT " + (queryDTO.getLimit() == null ? 10 : queryDTO.getLimit()));
        entities = list(queryWrapper);

        if (CollectionUtils.isNotEmpty(queryDTO.getTags())) {
            List<MemoryUnit> memoryUnits = memoryUnitMapper.selectWithJoinTags(queryDTO.getUserId(), queryDTO.getAgentId(), queryDTO.getTags(), timeRangeQuery ? timeRange.get(0) : null, timeRangeQuery ? timeRange.get(1) : null, 0L, 10L);
            // 移除entries中id已包含的
            memoryUnits.removeIf(memoryUnit -> entities.stream().anyMatch(entity -> entity.getId().equals(memoryUnit.getId())));
            entities.addAll(memoryUnits);
        }
        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private List<Date> parseTimeRange(MemoryUnitQueryDTO queryDTO) {
        //查询范围，可选值为：today|yesterday|recent|long-term|all
        String queryType = queryDTO.getQueryType();
        //时间范围（非必填），格式为：2023-01-01 00:00:00-2023-01-01 23:59:59
        //有可能为空
        String timeRange = queryDTO.getTimeRange();

        List<Date> dates = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        try {
            // 优先使用自定义时间范围
            if (StringUtils.isNotBlank(timeRange)) {
                String[] times = timeRange.split("-");
                if (times.length == 2) {
                    Date startTime = sdf.parse(times[0]);
                    Date endTime = sdf.parse(times[1]);
                    dates.add(startTime);
                    dates.add(endTime);
                    return dates;
                }
            }

            // 根据查询类型计算时间范围
            Calendar calendar = Calendar.getInstance();
            Date startTime, endTime;

            switch (queryType) {
                case "today":
                    // 今天 00:00:00 - 当前时间
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    startTime = calendar.getTime();
                    endTime = new Date();
                    break;

                case "yesterday":
                    // 昨天 00:00:00 - 23:59:59
                    calendar.add(Calendar.DAY_OF_MONTH, -1);
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    startTime = calendar.getTime();

                    calendar.set(Calendar.HOUR_OF_DAY, 23);
                    calendar.set(Calendar.MINUTE, 59);
                    calendar.set(Calendar.SECOND, 59);
                    calendar.set(Calendar.MILLISECOND, 999);
                    endTime = calendar.getTime();
                    break;

                case "recent":
                    // 最近7天
                    calendar.add(Calendar.DAY_OF_MONTH, -7);
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    startTime = calendar.getTime();
                    endTime = new Date();
                    break;

                case "long-term":
                    // 最近30天
                    calendar.add(Calendar.DAY_OF_MONTH, -30);
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    startTime = calendar.getTime();
                    endTime = new Date();
                    break;

                case "all":
                    // 全部数据，返回空列表
                    return new ArrayList<>();

                default:
                    // 默认返回最近7天
                    calendar.add(Calendar.DAY_OF_MONTH, -7);
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    startTime = calendar.getTime();
                    endTime = new Date();
                    break;
            }

            dates.add(startTime);
            dates.add(endTime);

        } catch (ParseException e) {
            throw new RuntimeException("时间格式解析失败，正确格式为：yyyy-MM-dd HH:mm:ss-yyyy-MM-dd HH:mm:ss", e);
        }

        return dates;
    }

    @Override
    public List<MemoryUnitDTO> findByUserIdAndCategory(Long userId, Long agentId, String category, String subCategory) {
        List<MemoryUnit> entities = baseMapper.findByUserIdAndCategory(userId, agentId, category, subCategory);
        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryUnitDTO> findByAgentId(Long agentId) {
        List<MemoryUnit> entities = baseMapper.findByAgentId(agentId);
        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryUnitDTO> findByCategoryAndJsonKeyValue(Long tenantId, Long userId, String category, String subCategory, String jsonKey, String jsonValue) {
        log.info("根据分类和JSON字段值查询: tenantId={}, userId={}, category={}, subCategory={}, jsonKey={}, jsonValue={}",
                tenantId, userId, category, subCategory, jsonKey, jsonValue);

        List<MemoryUnit> entities = baseMapper.findByCategoryAndJsonKeyValue(tenantId, userId, category, subCategory, jsonKey, jsonValue);
        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryUnitDTO> findByCategoryAndJsonKeyValueLike(Long tenantId, Long userId, String category, String subCategory, String jsonKey, String jsonValue) {
        log.info("根据分类和JSON字段值查询(模糊匹配): tenantId={}, userId={}, category={}, subCategory={}, jsonKey={}, jsonValue={}",
                tenantId, userId, category, subCategory, jsonKey, jsonValue);

        List<MemoryUnit> entities = baseMapper.findByCategoryAndJsonKeyValueLike(tenantId, userId, category, subCategory, jsonKey, jsonValue);
        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryUnitDTO> findByCategoryAndJsonKeyValues(Long tenantId, Long userId, String category, String subCategory, Map<String, String> jsonKeyValues) {
        log.info("根据分类和JSON多字段值查询: tenantId={}, userId={}, category={}, subCategory={}, jsonKeyValues={}",
                tenantId, userId, category, subCategory, jsonKeyValues);

        List<MemoryUnit> entities = baseMapper.findByCategoryAndJsonKeyValues(tenantId, userId, category, subCategory, jsonKeyValues);
        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public MemoryUnitDTO toDTO(MemoryUnit entity) {
        if (entity == null) {
            return null;
        }

        return MemoryUnitDTO.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .category(entity.getCategory())
                .subCategory(entity.getSubCategory())
                .contentJson(entity.getContentJson())
                .isSensitive(entity.getIsSensitive())
                .status(entity.getStatus())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
