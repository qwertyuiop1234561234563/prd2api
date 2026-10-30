package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.entity.AssistantUserMemory;
import com.dbappsecurity.prd2api.mapper.AssistantUserMemoryMapper;
import com.dbappsecurity.prd2api.service.AssistantMemoryService;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 助手长期记忆实现
 * <p>
 * 记忆分两类来源：模型通过 save_memory 工具主动写入（AGENT），
 * 以及从固定句式规则抽取（RULE），无模型密钥时也能积累用户偏好。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryServiceImpl implements AssistantMemoryService {

    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int SCAN_LIMIT = 200;

    /** 记住 XXX / 帮我记住：XXX / 记住我 XXX */
    private static final Pattern REMEMBER = Pattern.compile(
            "(?:帮我)?记住(?:一下)?(?:[：:，,\\s]+|我)(.{1,200})");
    /** 我叫 XXX / 我的名字是 XXX；排除“我叫什么”“我的名字是什么”这类提问 */
    private static final Pattern NAME = Pattern.compile(
            "我(?:叫|的名字是)(?![什么啥谁哪多少])([\\p{IsHan}A-Za-z0-9_]{1,16})");

    private final AssistantUserMemoryMapper memoryMapper;
    private final Prd2ApiProperties properties;

    @Override
    public MemoryItem save(String key, String value, String source) {
        String safeKey = StrUtil.maxLength(StrUtil.blankToDefault(key, "").trim(), MAX_KEY_LENGTH);
        String safeValue = StrUtil.maxLength(StrUtil.blankToDefault(value, "").trim(), MAX_VALUE_LENGTH);
        if (StrUtil.isBlank(safeKey) || StrUtil.isBlank(safeValue)) {
            return null;
        }
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        LocalDateTime now = LocalDateTime.now();

        AssistantUserMemory exists = memoryMapper.selectOne(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, tenantId)
                .eq(AssistantUserMemory::getUserId, userId)
                .eq(AssistantUserMemory::getMemoryKey, safeKey)
                .last("limit 1"));
        if (exists != null) {
            exists.setMemoryValue(safeValue);
            exists.setSource(StrUtil.blankToDefault(source, "AGENT"));
            exists.setUpdateTime(now);
            memoryMapper.updateById(exists);
            return new MemoryItem(exists.getId(), exists.getMemoryKey(), exists.getMemoryValue(), 1.0D);
        }

        AssistantUserMemory memory = new AssistantUserMemory();
        memory.setTenantId(tenantId);
        memory.setUserId(userId);
        memory.setMemoryKey(safeKey);
        memory.setMemoryValue(safeValue);
        memory.setSource(StrUtil.blankToDefault(source, "AGENT"));
        memory.setHitCount(0);
        memory.setCreateTime(now);
        memory.setUpdateTime(now);
        memoryMapper.insert(memory);
        trimOverflow(tenantId, userId);
        log.info("助手记忆写入 tenantId={} userId={} key={} source={}", tenantId, userId, safeKey, source);
        return new MemoryItem(memory.getId(), memory.getMemoryKey(), memory.getMemoryValue(), 1.0D);
    }

    @Override
    public List<MemoryItem> recall(String query, int limit) {
        int size = limit > 0 ? limit : Math.max(1, properties.getAgent().getMemoryInjectLimit());
        List<AssistantUserMemory> facts = recentFacts();
        if (facts.isEmpty()) {
            return List.of();
        }
        if (StrUtil.isBlank(query)) {
            return facts.stream().limit(size)
                    .map(item -> new MemoryItem(item.getId(), item.getMemoryKey(), item.getMemoryValue(), 1.0D))
                    .toList();
        }
        List<MemoryItem> scored = new ArrayList<>(facts.size());
        for (AssistantUserMemory fact : facts) {
            double score = SimilarityUtil.textSimilarity(query,
                    fact.getMemoryKey() + " " + fact.getMemoryValue());
            scored.add(new MemoryItem(fact.getId(), fact.getMemoryKey(), fact.getMemoryValue(), score));
        }
        scored.sort(Comparator.comparingDouble(MemoryItem::score).reversed());
        return scored.stream().limit(size).toList();
    }

    @Override
    public List<AssistantUserMemory> list(int limit) {
        int size = limit > 0 ? Math.min(limit, 100) : 50;
        return memoryMapper.selectList(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, TenantContextUtil.requireTenantId())
                .eq(AssistantUserMemory::getUserId, TenantContextUtil.requireUserId())
                .orderByDesc(AssistantUserMemory::getUpdateTime)
                .last("limit " + size));
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            return;
        }
        memoryMapper.delete(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getId, id)
                .eq(AssistantUserMemory::getTenantId, TenantContextUtil.requireTenantId())
                .eq(AssistantUserMemory::getUserId, TenantContextUtil.requireUserId()));
    }

    @Override
    public List<MemoryItem> extract(String message) {
        if (StrUtil.isBlank(message)) {
            return List.of();
        }
        List<MemoryItem> saved = new ArrayList<>();
        Matcher nameMatcher = NAME.matcher(message);
        if (nameMatcher.find()) {
            addIfPresent(saved, save("称呼", nameMatcher.group(1), "RULE"));
        }
        Matcher rememberMatcher = REMEMBER.matcher(message);
        if (rememberMatcher.find()) {
            String value = cutTrailingPunctuation(rememberMatcher.group(1));
            if (StrUtil.isNotBlank(value)) {
                String key = isPreference(value)
                        ? "偏好" : "备注-" + StrUtil.subPre(DigestUtil.md5Hex(value), 8);
                addIfPresent(saved, save(key, value, "RULE"));
            }
        }
        return saved;
    }

    private boolean isPreference(String value) {
        return value.contains("喜欢") || value.contains("偏好") || value.contains("习惯")
                || value.contains("常用") || value.contains("希望");
    }

    @Override
    public long count() {
        return memoryMapper.selectCount(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, TenantContextUtil.requireTenantId())
                .eq(AssistantUserMemory::getUserId, TenantContextUtil.requireUserId()));
    }

    private List<AssistantUserMemory> recentFacts() {
        return memoryMapper.selectList(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, TenantContextUtil.requireTenantId())
                .eq(AssistantUserMemory::getUserId, TenantContextUtil.requireUserId())
                .orderByDesc(AssistantUserMemory::getUpdateTime)
                .last("limit " + SCAN_LIMIT));
    }

    /**
     * 超过上限时淘汰最久未更新的记忆，避免无限增长
     */
    private void trimOverflow(String tenantId, String userId) {
        int max = properties.getAgent().getMemoryMaxFacts();
        if (max <= 0) {
            return;
        }
        Long total = memoryMapper.selectCount(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, tenantId)
                .eq(AssistantUserMemory::getUserId, userId));
        if (total == null || total <= max) {
            return;
        }
        List<AssistantUserMemory> overflow = memoryMapper.selectList(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, tenantId)
                .eq(AssistantUserMemory::getUserId, userId)
                .orderByAsc(AssistantUserMemory::getUpdateTime)
                .last("limit " + (total - max)));
        if (!overflow.isEmpty()) {
            memoryMapper.deleteBatchIds(overflow.stream().map(AssistantUserMemory::getId).toList());
        }
    }

    private void addIfPresent(List<MemoryItem> list, MemoryItem item) {
        if (item != null) {
            list.add(item);
        }
    }

    private String cutTrailingPunctuation(String value) {
        return value == null ? "" : value.replaceAll("[。！？!?，,；;\\s]+$", "").trim();
    }
}
