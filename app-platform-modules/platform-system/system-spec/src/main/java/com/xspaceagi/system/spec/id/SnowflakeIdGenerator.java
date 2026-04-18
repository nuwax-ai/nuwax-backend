package com.xspaceagi.system.spec.id;

import org.springframework.stereotype.Component;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.PostConstruct;

/**
 * 基于雪花算法的分布式ID生成器
 */
@Component
public class SnowflakeIdGenerator implements IdGenerator {

    // 数据中心ID，目前单实例，不配置，直接写死
    // @Value("${id.snowflake.datacenter-id:1}")
    private long datacenterId = 1L;

    // 数据中心内的机器/实例ID，目前单实例，不配置，直接写死
    // @Value("${id.snowflake.worker-id:1}")
    private long workerId = 1L;

    private Snowflake snowflake;

    @PostConstruct
    public void init() {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    @Override
    public long nextId() {
        return snowflake.nextId();
    }

    /**
     * 生成指定位数的ID
     * 注意：位数越少，重复风险越高！
     * 
     * 建议：
     * - 19位：完全不重复（原始雪花ID）
     * - 16-18位：截取后N位，基本不重复
     * - 13-15位：中等并发场景可用，有一定重复风险
     * - ≤12位：高并发场景下重复概率较高，不推荐
     */
    @Override
    public long nextId(int digits) {
        if (digits < 1 || digits > 19) {
            throw new IllegalArgumentException("Bit count must be between 1 and 19");
        }

        long id = snowflake.nextId();

        if (digits >= 19) {
            return id;
        }

        id = Math.abs(id);

        String idStr = String.valueOf(id);

        // 如果本身不足指定位数，直接返回
        if (idStr.length() <= digits) {
            return id;
        }

        // 截取后N位（保留时间戳的后部分+序列号）
        String truncated = idStr.substring(idStr.length() - digits);

        return Long.parseLong(truncated);
    }

    @Override
    public String nextIdStr() {
        return snowflake.nextIdStr();
    }

    @Override
    public String nextIdStr(int digits) {
        long id = nextId(digits);
        return String.valueOf(id);
    }
}
