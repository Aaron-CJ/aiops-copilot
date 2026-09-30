package com.aiops.aiopscopilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * prod profile 安全启动自检（fail-fast）。
 * <p>
 * 鉴权装配（{@link ApiTokenSecurityConfig}）是"值语义"：AIOPS_API_TOKEN 非空才注册过滤器。
 * 这防住了"忘切 profile 导致生产裸奔"，但防不住"prod 忘设环境变量"——token 为空时
 * 系统照常启动且无任何告警。本守卫补上最后一道：prod 下安全默认值缺失直接拒绝启动，
 * 让配置错误在部署时刻暴露，而不是在第一次安全事件时暴露。
 * <p>
 * 校验项：
 * <ol>
 *   <li>AIOPS_API_TOKEN 必须非空——否则 /api/** 全部无鉴权</li>
 *   <li>Milvus 密码不得为知名默认值 'milvus'——向量库含企业知识库，弱凭据即数据泄露</li>
 * </ol>
 * 用构造器校验而非 ApplicationRunner：bean 创建阶段即失败，启动日志最前部可见原因。
 */
@Component
@Profile("prod")
public class ProdSecurityGuard {

    /** Milvus 部署的出厂默认密码（docker/milvus-standalone-docker-compose.yml 与官方文档一致） */
    static final String MILVUS_DEFAULT_PASSWORD = "milvus";

    public ProdSecurityGuard(@Value("${aiops.security.token:}") String apiToken,
                             @Value("${spring.ai.vectorstore.milvus.client.password:}") String milvusPassword) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException(
                    "prod 环境必须设置 AIOPS_API_TOKEN（/api/** 鉴权令牌），拒绝以无鉴权状态启动");
        }
        if (MILVUS_DEFAULT_PASSWORD.equals(milvusPassword)) {
            throw new IllegalStateException(
                    "prod 环境必须通过 MILVUS_PASSWORD 修改 Milvus 默认密码，拒绝以出厂凭据启动");
        }
    }
}
