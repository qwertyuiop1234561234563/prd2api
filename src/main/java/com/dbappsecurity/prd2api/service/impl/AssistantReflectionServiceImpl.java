package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.service.AssistantReflectionService;
import com.dbappsecurity.prd2api.util.MetricUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 失败反思实现
 * <p>
 * 先做确定性的规则归因（保证低延迟、可复现），再把“原因 → 下一步”沉淀到 Redis；
 * 同一工具再次失败时能把历史教训带出来，避免同类错误反复出现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantReflectionServiceImpl implements AssistantReflectionService {

    private static final long LESSON_TTL_DAYS = 7L;

    private final StringRedisTemplate stringRedisTemplate;
    private final Prd2ApiProperties properties;
    private final MetricUtil metricUtil;

    @Override
    public Lesson reflect(String toolName, String arguments, String error) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        if (config == null || !config.isReflectionEnabled()) {
            return null;
        }
        String diagnosis = diagnose(error);
        String suggestion = suggest(error);
        boolean reused = false;
        try {
            String key = key();
            String field = StrUtil.blankToDefault(toolName, "unknown");
            String existing = (String) stringRedisTemplate.opsForHash().get(key, field);
            if (StrUtil.isNotBlank(existing)) {
                reused = true;
                suggestion = suggestion + "（历史同类失败教训：" + StrUtil.maxLength(existing, 60) + "）";
            }
            stringRedisTemplate.opsForHash().put(key, field,
                    StrUtil.maxLength(diagnosis + "→" + suggestion, 120));
            stringRedisTemplate.expire(key, LESSON_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("失败反思写入教训失败 tool={} msg={}", toolName, e.getMessage());
        }
        metricUtil.agentReflection(reused);
        log.info("失败反思 tool={} args={} diagnosis={} reused={}", toolName,
                StrUtil.maxLength(arguments, 60), diagnosis, reused);
        return new Lesson(diagnosis, suggestion, reused);
    }

    private String diagnose(String error) {
        String text = StrUtil.blankToDefault(error, "");
        if (contains(text, "未检索到", "没有找到", "无相关", "没有匹配")) {
            return "知识库未覆盖该主题，或关键词与文档表述不一致";
        }
        if (contains(text, "超时", "timeout", "Timeout")) {
            return "工具执行超时，多半是查询范围过大";
        }
        if (contains(text, "缺少", "必填", "参数", "格式")) {
            return "调用参数缺失或不合法";
        }
        if (contains(text, "不存在", "未找到", "无权限", "无权")) {
            return "目标对象不存在，或当前用户没有该数据的权限";
        }
        return "工具返回失败：" + StrUtil.maxLength(text, 80);
    }

    private String suggest(String error) {
        String text = StrUtil.blankToDefault(error, "");
        if (contains(text, "未检索到", "没有找到", "无相关", "没有匹配")) {
            return "换用更通用的关键词或改用系统查询工具，不要用相同参数重复调用";
        }
        if (contains(text, "超时", "timeout", "Timeout")) {
            return "缩小查询范围，或先查列表再按 ID 查询详情";
        }
        if (contains(text, "缺少", "必填", "参数", "格式")) {
            return "先向用户追问补齐参数，不要猜造";
        }
        if (contains(text, "不存在", "未找到", "无权限", "无权")) {
            return "先查询列表确认对象 ID，或说明权限限制";
        }
        return "不要重复相同调用，换工具或先向用户澄清";
    }

    private boolean contains(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String key() {
        String tenantId = StrUtil.blankToDefault(TenantContextUtil.getTenantId(), "default");
        String userKey = StrUtil.blankToDefault(TenantContextUtil.getUserKey(),
                StrUtil.blankToDefault(TenantContextUtil.getUserId(), "anon"));
        return RedisKeyConstant.ASSISTANT_REFLECTION_PREFIX + tenantId + ":" + userKey;
    }
}
