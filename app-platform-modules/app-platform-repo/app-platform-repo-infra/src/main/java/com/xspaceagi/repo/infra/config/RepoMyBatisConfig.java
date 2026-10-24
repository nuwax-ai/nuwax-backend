package com.xspaceagi.repo.infra.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 配置
 */
@Configuration
@MapperScan("com.xspaceagi.repo.infra.dao.mapper")
public class RepoMyBatisConfig {
}
