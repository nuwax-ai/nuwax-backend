package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeDocument;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeDocumentMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeDocumentService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;

/**
 * @author soddy
 * @description 针对表【knowledge_document(知识库-原始文档表)】的数据库操作Service实现
 * @createDate 2025-01-21 15:33:38
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument>
        implements KnowledgeDocumentService {

    @Resource
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Override
    public List<KnowledgeDocument> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeDocument::getYn, YnEnum.Y.getKey())
                .in(KnowledgeDocument::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public KnowledgeDocument queryOneInfoById(Long id) {
        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeDocument::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeDocument::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(KnowledgeDocument entity) {
        var updateObj = this.getById(entity.getId());

        if (Objects.isNull(updateObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        entity.setCreated(null);
        entity.setModified(null);

        this.updateById(entity);

        return entity.getId();
    }

    @Override
    public Long addInfo(KnowledgeDocument entity) {
        entity.setId(null);
        entity.setCreated(null);
        entity.setModified(null);

        this.save(entity);

        return entity.getId();
    }

    @Override
    public void deleteById(Long id) {
        this.removeById(id);
    }

    @Override
    public List<KnowledgeDocument> queryListByConfigId(Long configId) {
        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeDocument::getYn, YnEnum.Y.getKey())
                .in(KnowledgeDocument::getKbId, configId);
        return this.list(queryWrapper);
    }

    @Override
    public void deleteByConfigyId(Long kbId) {

        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeDocument::getKbId, kbId);

        this.remove(queryWrapper);
    }

    @Override
    public Long queryTotalFileSize(Long kbId) {

        var result = this.knowledgeDocumentMapper.queryTotalFileSize(kbId);
        // result 如果为空,默认为0
        if (Objects.isNull(result)) {
            return 0L;
        }
        return result;
    }

    @Override
    public List<KnowledgeDocument> queryDocByKbId(Long kbId) {
        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeDocument::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeDocument::getKbId, kbId);
        return this.list(queryWrapper);
    }

}
