package com.dbappsecurity.prd2api.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 限流熔断配置
 * <p>
 * 规则在应用内初始化，生产环境可改为接入 Sentinel 控制台动态下发。
 */
@Slf4j
@Component
public class SentinelConfig {

    /** 文件上传 */
    public static final String RES_UPLOAD = "prd2api:upload";
    /** 任务创建 */
    public static final String RES_TASK_CREATE = "prd2api:task:create";
    /** LLM 调用 */
    public static final String RES_LLM = "prd2api:llm";

    /**
     * Sentinel 默认把日志写到 ${user.home}/logs/csp，容器或受限环境下不可写会导致启动失败，
     * 这里统一改到应用数据目录（必须在加载 FlowRuleManager 之前设置）。
     */
    @Value("${prd2api.sentinel-log-dir:./data/logs/csp}")
    private String sentinelLogDir;

    @PostConstruct
    public void initRules() {
        System.setProperty("csp.sentinel.log.dir",
                Paths.get(sentinelLogDir).toAbsolutePath().normalize().toString());
        List<FlowRule> rules = new ArrayList<>();
        rules.add(flowRule(RES_UPLOAD, 50));
        rules.add(flowRule(RES_TASK_CREATE, 30));
        rules.add(flowRule(RES_LLM, 120));
        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 限流规则加载完成 rules={}", rules.size());
    }

    private FlowRule flowRule(String resource, double qps) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        return rule;
    }

    @PreDestroy
    public void clearRules() {
        FlowRuleManager.loadRules(new ArrayList<>());
    }
}
