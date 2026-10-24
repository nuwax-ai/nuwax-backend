package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.SensitiveWordApplicationService;
import com.xspaceagi.system.infra.dao.entity.SensitiveWord;
import com.xspaceagi.system.infra.dao.service.SensitiveWordService;
import com.xspaceagi.system.sdk.service.ISensitiveWordRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.*;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 敏感词管控应用服务实现
 */
@Slf4j
@Service
public class SensitiveWordApplicationServiceImpl implements SensitiveWordApplicationService, ISensitiveWordRpcService {

    /**
     * 替换策略使用的遮蔽字符
     */
    private static final String MASK_CHAR = "*";

    /**
     * 词表本地缓存：checkText 会对请求体的每个内容字段各调用一次（模型代理场景一次请求可达上百个字段），
     * 不缓存则每次都查库并重复编译正则，拖垮调用方线程；词表变更频率极低，短 TTL + CRUD 主动失效
     */
    private static final String WORD_CACHE_KEY = "sensitive-word-cache";
    private static final long WORD_CACHE_TTL_SECONDS = 30;

    /**
     * 词表条目：正则词预编译（编译失败的词在构建缓存时跳过，沿用入库校验+兜底防御的既有行为）
     */
    private record CompiledWord(SensitiveWord word, Pattern pattern) {
    }

    @Resource
    private SensitiveWordService sensitiveWordService;

    @Value("${sensitive.enabled:true}")
    private String enabledSensitive;

    @Override
    public ReqResult<SensitiveWord> create(SensitiveWordCreateDto dto, UserContext userContext) {
        log.info("[create] Create sensitive word, word={}, category={}, matchType={}, action={}",
                dto.getWord(), dto.getCategory(), dto.getMatchType(), dto.getAction());

        // 校验分类、匹配方式、触发策略
        String validError = validateFields(dto.getWord(), dto.getCategory(), dto.getMatchType(), dto.getAction());
        if (validError != null) {
            return ReqResult.error("0001", validError);
        }

        // 校验状态
        if (dto.getStatus() != null && StatusEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("0001", "状态无效");
        }

        // 校验敏感词是否已存在
        SensitiveWord existing = getByWord(dto.getWord());
        if (existing != null) {
            log.error("[create] 敏感词已存在, word={}", dto.getWord());
            return ReqResult.error("0002", "敏感词已存在");
        }

        SensitiveWord sensitiveWord = new SensitiveWord();
        sensitiveWord.setTenantId(userContext.getTenantId());
        sensitiveWord.setWord(dto.getWord());
        sensitiveWord.setCategory(dto.getCategory());
        sensitiveWord.setMatchType(dto.getMatchType());
        sensitiveWord.setAction(dto.getAction());
        sensitiveWord.setReplaceChar(dto.getReplaceChar());
        sensitiveWord.setStatus(dto.getStatus() == null ? StatusEnum.ENABLED.getCode() : dto.getStatus());

        sensitiveWordService.save(sensitiveWord);
        SimpleJvmHashCache.removeHash(WORD_CACHE_KEY, String.valueOf(userContext.getTenantId()));

        log.info("[create] 创建敏感词成功, id={}", sensitiveWord.getId());
        return ReqResult.success(sensitiveWord);
    }

    @Override
    public ReqResult<SensitiveWord> update(SensitiveWordUpdateDto dto, UserContext userContext) {
        log.info("[update] Update sensitive word, id={}, word={}, category={}, matchType={}, action={}",
                dto.getId(), dto.getWord(), dto.getCategory(), dto.getMatchType(), dto.getAction());

        SensitiveWord existing = sensitiveWordService.getById(dto.getId());
        if (existing == null) {
            log.error("[update] 敏感词不存在, id={}", dto.getId());
            return ReqResult.error("0003", "敏感词不存在");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[update] 无权限操作该敏感词, id={}", dto.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        // 合并后的完整值做整体校验（正则合法性等依赖完整匹配方式）
        String word = dto.getWord() != null ? dto.getWord() : existing.getWord();
        String category = dto.getCategory() != null ? dto.getCategory() : existing.getCategory();
        String matchType = dto.getMatchType() != null ? dto.getMatchType() : existing.getMatchType();
        String action = dto.getAction() != null ? dto.getAction() : existing.getAction();
        String validError = validateFields(word, category, matchType, action);
        if (validError != null) {
            return ReqResult.error("0001", validError);
        }

        // 校验敏感词是否已存在
        if (dto.getWord() != null && !dto.getWord().equals(existing.getWord())) {
            SensitiveWord duplicate = getByWord(dto.getWord());
            if (duplicate != null) {
                log.error("[update] 敏感词已被其他记录使用, word={}", dto.getWord());
                return ReqResult.error("0004", "敏感词已被其他记录使用");
            }
        }

        existing.setWord(word);
        existing.setCategory(category);
        existing.setMatchType(matchType);
        existing.setAction(action);
        existing.setReplaceChar(dto.getReplaceChar() != null ? dto.getReplaceChar() : existing.getReplaceChar());

        sensitiveWordService.updateById(existing);
        SimpleJvmHashCache.removeHash(WORD_CACHE_KEY, String.valueOf(userContext.getTenantId()));

        log.info("[update] 更新敏感词成功, id={}", existing.getId());
        return ReqResult.success(existing);
    }

    @Override
    public ReqResult<Void> delete(Long id, UserContext userContext) {
        log.info("[delete] Delete sensitive word, id={}", id);

        SensitiveWord existing = sensitiveWordService.getById(id);
        if (existing == null) {
            log.error("[delete] 敏感词不存在, id={}", id);
            return ReqResult.error("0003", "敏感词不存在");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[delete] 无权限操作该敏感词, id={}", id);
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        sensitiveWordService.removeById(id);
        SimpleJvmHashCache.removeHash(WORD_CACHE_KEY, String.valueOf(userContext.getTenantId()));

        log.info("[delete] 删除敏感词成功, id={}", id);
        return ReqResult.success(null);
    }

    @Override
    public ReqResult<Void> updateStatus(SensitiveWordStatusDto dto, UserContext userContext) {
        log.info("[updateStatus] Update sensitive word status, id={}, status={}", dto.getId(), dto.getStatus());

        if (StatusEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("0001", "状态无效");
        }

        SensitiveWord existing = sensitiveWordService.getById(dto.getId());
        if (existing == null) {
            log.error("[updateStatus] 敏感词不存在, id={}", dto.getId());
            return ReqResult.error("0003", "敏感词不存在");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[updateStatus] 无权限操作该敏感词, id={}", dto.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        SensitiveWord update = new SensitiveWord();
        update.setId(existing.getId());
        update.setStatus(dto.getStatus());
        sensitiveWordService.updateById(update);
        SimpleJvmHashCache.removeHash(WORD_CACHE_KEY, String.valueOf(userContext.getTenantId()));

        log.info("[updateStatus] 更新敏感词状态成功, id={}, status={}", dto.getId(), dto.getStatus());
        return ReqResult.success(null);
    }

    @Override
    public Page<SensitiveWord> pageQuery(PageQueryVo<SensitiveWordQueryDto> pageQueryVo) {
        SensitiveWordQueryDto queryFilter = pageQueryVo.getQueryFilter() == null ? new SensitiveWordQueryDto() : pageQueryVo.getQueryFilter();
        return sensitiveWordService.page(
                new Page<>(pageQueryVo.getCurrent(), pageQueryVo.getPageSize()),
                buildFilterWrapper(queryFilter)
        );
    }

    @Override
    public List<SensitiveWord> listAll(SensitiveWordQueryDto dto) {
        return sensitiveWordService.list(buildFilterWrapper(dto));
    }

    @Override
    public List<SensitiveWord> listEnabled(Long tenantId) {
        return TenantFunctions.callWithIgnoreCheck(() -> sensitiveWordService.list(
                Wrappers.<SensitiveWord>lambdaQuery()
                        .eq(SensitiveWord::getTenantId, tenantId)
                        .eq(SensitiveWord::getStatus, StatusEnum.ENABLED.getCode())
                        .orderByDesc(SensitiveWord::getId)
        ));
    }

    /**
     * 取启用词表（正则预编译），带租户级本地缓存；CRUD 主动失效，TTL 兜底
     */
    @SuppressWarnings("unchecked")
    private List<CompiledWord> listEnabledCompiled(Long tenantId) {
        String cacheField = String.valueOf(tenantId);
        Object cached = SimpleJvmHashCache.getHash(WORD_CACHE_KEY, cacheField);
        if (cached instanceof List) {
            return (List<CompiledWord>) cached;
        }
        List<CompiledWord> compiledWords = new ArrayList<>();
        for (SensitiveWord sensitiveWord : listEnabled(tenantId)) {
            if (SensitiveWordMatchTypeEnum.REGEX.getCode().equals(sensitiveWord.getMatchType())) {
                try {
                    compiledWords.add(new CompiledWord(sensitiveWord, Pattern.compile(sensitiveWord.getWord())));
                } catch (PatternSyntaxException e) {
                    // 入库时已校验，此处兜底防御
                    log.error("[checkText] 敏感词正则表达式非法, id={}, word={}", sensitiveWord.getId(), sensitiveWord.getWord());
                }
            } else {
                compiledWords.add(new CompiledWord(sensitiveWord, null));
            }
        }
        SimpleJvmHashCache.putHash(WORD_CACHE_KEY, cacheField, compiledWords, WORD_CACHE_TTL_SECONDS);
        return compiledWords;
    }

    @Override
    public SensitiveWordCheckResultDto checkText(Long tenantId, String text) {
        if (StringUtils.isBlank(text)) {
            return SensitiveWordCheckResultDto.builder()
                    .text(text)
                    .filteredText(text)
                    .disconnect(false)
                    .hits(List.of())
                    .build();
        }

        List<SensitiveWord> hits = new ArrayList<>();
        boolean disconnect = false;
        String filteredText = text;

        for (CompiledWord compiledWord : listEnabledCompiled(tenantId)) {
            SensitiveWord sensitiveWord = compiledWord.word();
            // 命中判定一律基于原文，避免先应用的替换策略把后续敏感词遮蔽掉
            if (SensitiveWordMatchTypeEnum.REGEX.getCode().equals(sensitiveWord.getMatchType())) {
                Pattern pattern = compiledWord.pattern();
                if (pattern.matcher(text).find()) {
                    hits.add(sensitiveWord);
                    if (SensitiveWordActionEnum.DISCONNECT.getCode().equals(sensitiveWord.getAction())) {
                        disconnect = true;
                    } else if (SensitiveWordActionEnum.REPLACE.getCode().equals(sensitiveWord.getAction())) {
                        filteredText = pattern.matcher(filteredText).replaceAll(match -> mask(match.group(), sensitiveWord.getReplaceChar()));
                    }
                }
            } else {
                if (text.contains(sensitiveWord.getWord())) {
                    hits.add(sensitiveWord);
                    if (SensitiveWordActionEnum.DISCONNECT.getCode().equals(sensitiveWord.getAction())) {
                        disconnect = true;
                    } else if (SensitiveWordActionEnum.REPLACE.getCode().equals(sensitiveWord.getAction())) {
                        filteredText = filteredText.replace(sensitiveWord.getWord(), mask(sensitiveWord.getWord(), sensitiveWord.getReplaceChar()));
                    }
                }
            }
        }

        return SensitiveWordCheckResultDto.builder()
                .text(text)
                .filteredText(filteredText)
                .disconnect(disconnect)
                .hits(hits)
                .build();
    }

    @Override
    public String checkAndReturnNewText(Long tenantId, String text) {
        if (!"true".equals(enabledSensitive)) {
            return text;
        }
        SensitiveWordCheckResultDto sensitiveWordCheckResultDto = checkText(tenantId, text);
        if (sensitiveWordCheckResultDto.getDisconnect() != null && sensitiveWordCheckResultDto.getDisconnect()) {
            throw new InvalidParameterException();
        }
        return sensitiveWordCheckResultDto.getFilteredText();
    }

    @Override
    public boolean enabledSensitiveFilter() {
        return "true".equals(enabledSensitive);
    }

    /**
     * 按查询条件构建过滤 wrapper（分页查询与全量列表共用）
     */
    private LambdaQueryWrapper<SensitiveWord> buildFilterWrapper(SensitiveWordQueryDto dto) {
        return Wrappers.<SensitiveWord>lambdaQuery()
                .like(StringUtils.isNotBlank(dto.getWord()), SensitiveWord::getWord, dto.getWord())
                .eq(StringUtils.isNotBlank(dto.getCategory()), SensitiveWord::getCategory, dto.getCategory())
                .eq(StringUtils.isNotBlank(dto.getMatchType()), SensitiveWord::getMatchType, dto.getMatchType())
                .eq(StringUtils.isNotBlank(dto.getAction()), SensitiveWord::getAction, dto.getAction())
                .eq(dto.getStatus() != null, SensitiveWord::getStatus, dto.getStatus())
                .orderByDesc(SensitiveWord::getId);
    }

    /**
     * 按敏感词、分类、匹配方式、触发策略做完整校验，返回错误描述；通过时返回 null
     */
    private String validateFields(String word, String category, String matchType, String action) {
        if (StringUtils.isBlank(word)) {
            return "敏感词不能为空";
        }
        if (SensitiveWordCategoryEnum.isInValid(category)) {
            return "敏感词分类无效";
        }
        if (SensitiveWordMatchTypeEnum.isInValid(matchType)) {
            return "匹配方式无效";
        }
        if (SensitiveWordActionEnum.isInValid(action)) {
            return "触发策略无效";
        }
        if (SensitiveWordMatchTypeEnum.REGEX.getCode().equals(matchType)) {
            try {
                Pattern.compile(word);
            } catch (PatternSyntaxException e) {
                return "正则表达式非法：" + e.getDescription();
            }
        }
        return null;
    }

    /**
     * 查询当前租户下指定敏感词
     */
    private SensitiveWord getByWord(String word) {
        return sensitiveWordService.getOne(
                Wrappers.<SensitiveWord>lambdaQuery()
                        .eq(SensitiveWord::getWord, word)
                        .eq(SensitiveWord::getTenantId, RequestContext.get().getTenantId())
                        .last("limit 1")
        );
    }

    /**
     * 用替换字符遮蔽命中内容，保持长度一致；替换字符为空时回退 *
     */
    private String mask(String source, String replaceChar) {
        return (StringUtils.isBlank(replaceChar) ? MASK_CHAR : replaceChar).repeat(source.length());
    }
}
