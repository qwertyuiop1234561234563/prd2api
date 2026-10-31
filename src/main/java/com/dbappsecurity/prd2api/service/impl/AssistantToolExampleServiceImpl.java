package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.entity.AssistantAgentTrace;
import com.dbappsecurity.prd2api.mapper.AssistantAgentTraceMapper;
import com.dbappsecurity.prd2api.service.AssistantToolExampleService;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具样例沉淀实现
 * <p>
 * 只挖“成功且非空参数”的轨迹：失败调用的参数本身可能就是错的，不能拿来当范例。
 * 同一工具同参数按出现次数排序，次数越多说明格式越稳定；样例条数和总字符数都有硬上限，
 * 避免提示词膨胀反而稀释了工具定义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolExampleServiceImpl implements AssistantToolExampleService {

    private static final int DEFAULT_SCAN = 3000;
    private static final int MAX_SCAN = 5000;
    /** 同一条样例至少被成功调用两次才采信，避免偶发参数被当成模板 */
    private static final int MIN_EXAMPLE_HITS = 2;
    /** 注入块字符上限，控制提示词成本 */
    private static final int MAX_BLOCK_CHARS = 1200;
    private static final int MAX_ARGS_CHARS = 100;
    private static final int MAX_RESULT_CHARS = 60;

    private final AssistantAgentTraceMapper traceMapper;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Block block() {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isToolExampleEnabled()) {
            return new Block("", 0);
        }
        String key = RedisKeyConstant.assistantToolExample(
                TenantContextUtil.requireTenantId(), TenantContextUtil.requireUserKey());
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cached)) {
                return objectMapper.readValue(cached, Block.class);
            }
        } catch (Exception e) {
            log.debug("工具样例缓存读取失败 msg={}", e.getMessage());
        }
        Report report = mine(Math.max(1, config.getToolExampleDays()), DEFAULT_SCAN, config);
        Block block = buildBlock(report.items(), config);
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(block),
                    config.getToolExampleTtlSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("工具样例缓存写入失败 msg={}", e.getMessage());
        }
        log.info("工具样例生成 tools={} examples={} chars={}",
                report.toolsCovered(), block.examples(), block.text().length());
        return block;
    }

    @Override
    public Report report(int days, int limit) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isToolExampleEnabled()) {
            throw new BizException(BizErrorCodeEnum.FORBIDDEN, "工具样例沉淀已关闭");
        }
        int windowDays = days <= 0 ? Math.max(1, config.getToolExampleDays()) : Math.min(days, 90);
        int cap = limit <= 0 ? DEFAULT_SCAN : Math.min(limit, MAX_SCAN);
        Report report = mine(windowDays, cap, config);
        Block block = buildBlock(report.items(), config);
        List<String> notes = new ArrayList<>();
        if (report.items().isEmpty()) {
            notes.add("近期没有足够的成功工具轨迹（同参数至少成功 2 次且工具累计成功 "
                    + config.getToolExampleMinSuccess() + " 次），暂不注入样例");
        } else {
            notes.add("样例只取自成功轨迹且参数非空，失败调用的参数不会作为范例");
            notes.add("同参数至少成功 " + MIN_EXAMPLE_HITS + " 次才采信，单工具最多 "
                    + config.getToolExampleMaxPerTool() + " 条，总量不超过 " + config.getToolExampleMaxTotal() + " 条");
        }
        log.info("工具样例报告 tools={} examples={} promptChars={}",
                report.toolsCovered(), report.items().size(), block.text().length());
        return new Report(windowDays, report.scannedTraces(), report.toolsCovered(),
                report.items().size(), block.text().length(), report.items(), notes);
    }

    /**
     * 挖掘样例：先按工具聚合成功次数，再按“工具+参数”聚合，最后做条数与字符上限裁剪
     */
    private Report mine(int days, int cap, Prd2ApiProperties.Agent config) {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        List<AssistantAgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AssistantAgentTrace>()
                .eq(AssistantAgentTrace::getTenantId, tenantId)
                .eq(AssistantAgentTrace::getUserId, userId)
                .eq(AssistantAgentTrace::getStepType, "TOOL")
                .eq(AssistantAgentTrace::getSuccess, 1)
                .ge(AssistantAgentTrace::getCreateTime, LocalDateTime.now().minusDays(days))
                .orderByDesc(AssistantAgentTrace::getId)
                .last("limit " + cap));

        Map<String, ToolAcc> toolAccMap = new LinkedHashMap<>();
        for (AssistantAgentTrace trace : traces) {
            String tool = StrUtil.blankToDefault(trace.getToolName(), "");
            if (StrUtil.isBlank(tool)) {
                continue;
            }
            ToolAcc acc = toolAccMap.computeIfAbsent(tool, key -> new ToolAcc());
            acc.successes++;
            String args = StrUtil.blankToDefault(trace.getToolArgs(), "");
            if (StrUtil.isBlank(args) || "{}".equals(args.trim())) {
                continue;
            }
            Candidate candidate = acc.candidates.computeIfAbsent(args, key -> new Candidate(args,
                    StrUtil.blankToDefault(trace.getToolResult(), "")));
            candidate.hits++;
        }

        List<Example> picked = new ArrayList<>();
        int covered = 0;
        for (Map.Entry<String, ToolAcc> entry : toolAccMap.entrySet()) {
            ToolAcc acc = entry.getValue();
            if (acc.successes < config.getToolExampleMinSuccess()) {
                continue;
            }
            List<Candidate> usable = acc.candidates.values().stream()
                    .filter(candidate -> candidate.hits >= MIN_EXAMPLE_HITS)
                    .sorted(Comparator.comparingInt((Candidate c) -> c.hits).reversed()
                            .thenComparingInt(c -> c.args.length()))
                    .toList();
            if (usable.isEmpty()) {
                continue;
            }
            int take = Math.min(Math.max(1, config.getToolExampleMaxPerTool()), usable.size());
            for (int i = 0; i < take && picked.size() < config.getToolExampleMaxTotal(); i++) {
                Candidate candidate = usable.get(i);
                picked.add(new Example(entry.getKey(),
                        StrUtil.maxLength(candidate.args, MAX_ARGS_CHARS),
                        StrUtil.maxLength(candidate.result, MAX_RESULT_CHARS),
                        candidate.hits));
            }
            covered++;
        }
        return new Report(days, traces.size(), covered, picked.size(), 0, picked, List.of());
    }

    /**
     * 拼装注入块：总字符超限时按工具顺序截断，宁可少给样例也不挤占工具定义
     */
    private Block buildBlock(List<Example> examples, Prd2ApiProperties.Agent config) {
        if (examples == null || examples.isEmpty()) {
            return new Block("", 0);
        }
        StringBuilder sb = new StringBuilder("\n\n【历史成功工具调用样例】\n")
                .append("以下样例来自本账号真实成功的调用，仅用于参考参数格式，不要照抄参数值：\n");
        int count = 0;
        for (Example example : examples) {
            String line = "- " + example.toolName() + "(" + example.arguments() + ") → "
                    + StrUtil.blankToDefault(example.resultSummary(), "-") + "\n";
            if (sb.length() + line.length() > MAX_BLOCK_CHARS) {
                break;
            }
            sb.append(line);
            count++;
        }
        if (count == 0) {
            return new Block("", 0);
        }
        return new Block(sb.toString(), count);
    }

    /** 单工具累计器 */
    private static final class ToolAcc {
        private int successes;
        private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    }

    /** 同参数候选 */
    private static final class Candidate {
        private final String args;
        private final String result;
        private int hits;

        private Candidate(String args, String result) {
            this.args = args;
            this.result = result;
        }
    }
}
