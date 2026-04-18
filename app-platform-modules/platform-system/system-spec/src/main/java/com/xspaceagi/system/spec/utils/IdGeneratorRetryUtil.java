package com.xspaceagi.system.spec.utils;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.id.IdGenerator;

import lombok.extern.slf4j.Slf4j;

/**
 * ID生成重试工具类
 * ID重复的时候自动重试生成新ID
 * 当使用较短位数的ID时（<16），存在重复风险，需要自动重试
 */
@Slf4j
public class IdGeneratorRetryUtil {

    /**
     * 执行方法，使用默认19位ID，指定重试次数
     */
    public static <T> T executeWithRetry(IdGenerator idGenerator,
            Function<Long, T> operation,
            String bizName,
            int maxRetryTimes) {
        return executeWithRetry(() -> idGenerator.nextId(), operation, bizName, maxRetryTimes);
    }

    /**
     * 执行方法，使用默认19位ID，指定ID位数、重试次数
     */
    public static <T> T executeWithRetry(IdGenerator idGenerator,
            int digits,
            Function<Long, T> operation,
            String bizName,
            int maxRetryTimes) {
        return executeWithRetry(() -> idGenerator.nextId(digits), operation, bizName, maxRetryTimes);
    }

    /**
     * 执行方法，使用默认19位ID，指定重试次数，无返回值
     */
    public static void executeWithRetryVoid(IdGenerator idGenerator,
                                            Consumer<Long> operation,
                                            String bizName,
                                            int maxRetryTimes) {
        executeWithRetry(idGenerator, id -> {
            operation.accept(id);
            return null;
        }, bizName, maxRetryTimes);
    }

    /**
     * 执行方法，使用默认19位ID，指定ID位数、重试次数，无返回值
     */
    public static void executeWithRetryVoid(IdGenerator idGenerator,
                                            int digits,
                                            Consumer<Long> operation,
                                            String bizName,
                                            int maxRetryTimes) {
        executeWithRetry(idGenerator, digits, id -> {
            operation.accept(id);
            return null;
        }, bizName, maxRetryTimes);
    }

    /**
     * 通用的重试执行方法
     * 
     * @param idSupplier    ID生成器
     * @param operation     业务操作，接收ID，返回结果
     * @param bizName       业务名称（用于日志）
     * @param maxRetryTimes 最大重试次数
     * @param <T>           返回结果类型
     * @return 业务操作结果
     */
    public static <T> T executeWithRetry(Supplier<Long> idSupplier,
            Function<Long, T> operation,
            String bizName,
            int maxRetryTimes) {
        if (maxRetryTimes < 1) {
            throw new IllegalArgumentException("Max retries must be greater than 0");
        }

        Long lastGeneratedId = null;

        for (int attempt = 1; attempt <= maxRetryTimes; attempt++) {
            Long id = idSupplier.get();// 生成新ID
            lastGeneratedId = id;

            try {
                // 执行业务操作
                T result = operation.apply(id);

                if (attempt > 1) {
                    log.info("{}成功，id={}, 重试次数={}", bizName, id, attempt);
                } else {
                    log.debug("{}成功，id={}", bizName, id);
                }

                return result;

            } catch (DataIntegrityViolationException e) {
                // ID重复异常
                log.warn("{}失败，ID重复，准备重试，attempt={}/{}, id={}, error={}",
                        bizName, attempt, maxRetryTimes, id, e.getMessage());

                if (attempt >= maxRetryTimes) {
                    log.error("{}失败，重试达到上限，lastId={}", bizName, id);
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemIdGenerateRetryFailed, bizName);
                }
                // 继续下一次重试
            }
        }

        // 理论上不会走到这里，但为了安全
        log.error("{}失败，未知原因，lastId={}", bizName, lastGeneratedId);
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemIdGenerateRetryFailed, bizName);
    }

}
