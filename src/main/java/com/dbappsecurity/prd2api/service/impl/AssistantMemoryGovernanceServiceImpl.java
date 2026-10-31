package com.dbappsecurity.prd2api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.entity.AssistantUserMemory;
import com.dbappsecurity.prd2api.mapper.AssistantUserMemoryMapper;
import com.dbappsecurity.prd2api.service.AssistantMemoryGovernanceService;
import com.dbappsecurity.prd2api.util.SimilarityUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 长期记忆治理实现
 * <p>
 * 冲突判定是启发式的：记忆键 bigram 相似度达到阈值、且取值不同，视为同一主题下了两条矛盾记忆，
 * 保留最近更新的一条。没有接向量模型，是有意为之——治理要可重复、可解释，且不产生额外调用成本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryGovernanceServiceImpl implements AssistantMemoryGovernanceService {

    /** 单次治理扫描上限，避免用户记忆很多时拖慢接口 */
    private static final int MAX_SCAN = 200;
    /** 冲突明细最多返回条数 */
    private static final int MAX_CONFLICTS = 20;
    /** 陈旧记忆明细最多返回条数 */
    private static final int MAX_STALE = 20;

    private final AssistantUserMemoryMapper memoryMapper;
    private final Prd2ApiProperties properties;

    @Override
    public Health health() {
        Prd2ApiProperties.Agent config = properties.getAgent();
        int staleDays = config == null ? 30 : config.getMemoryStaleDays();
        double threshold = config == null ? 0.3D : config.getMemoryConflictSimilarity();
        List<AssistantUserMemory> memories = scan();

        List<Fact> staleFacts = new ArrayList<>();
        int hitless = 0;
        for (AssistantUserMemory memory : memories) {
            Fact fact = toFact(memory);
            if (fact.hitCount() <= 0) {
                hitless++;
            }
            if (fact.ageDays() > staleDays) {
                staleFacts.add(fact);
            }
        }
        staleFacts.sort((a, b) -> Long.compare(b.ageDays(), a.ageDays()));
        if (staleFacts.size() > MAX_STALE) {
            staleFacts = new ArrayList<>(staleFacts.subList(0, MAX_STALE));
        }

        List<Conflict> conflicts = detectConflicts(memories, threshold);
        String verdict = !conflicts.isEmpty() ? "NEEDS_COMPACT"
                : (!staleFacts.isEmpty() ? "AGING" : "HEALTHY");
        log.info("记忆体检完成 total={} conflicts={} stale={} hitless={}",
                memories.size(), conflicts.size(), staleFacts.size(), hitless);
        return new Health(memories.size(), staleFacts.size(), hitless, conflicts.size(),
                conflicts, staleFacts, verdict);
    }

    @Override
    public Compaction compact(boolean apply) {
        Prd2ApiProperties.Agent config = properties.getAgent();
        double threshold = config == null ? 0.3D : config.getMemoryConflictSimilarity();
        List<Conflict> conflicts = detectConflicts(scan(), threshold);
        List<Long> removedIds = new ArrayList<>();
        if (apply) {
            for (Conflict conflict : conflicts) {
                try {
                    memoryMapper.deleteById(conflict.dropId());
                    removedIds.add(conflict.dropId());
                } catch (Exception e) {
                    log.warn("记忆压缩删除失败 id={} msg={}", conflict.dropId(), e.getMessage());
                }
            }
            log.warn("记忆压缩已执行 removed={} ids={}", removedIds.size(), removedIds);
        }
        return new Compaction(apply, conflicts.size(), removedIds.size(), removedIds);
    }

    /**
     * 冲突检测：键相似度 + 取值不同，保留更新时间较新的一条
     */
    private List<Conflict> detectConflicts(List<AssistantUserMemory> memories, double threshold) {
        List<Conflict> conflicts = new ArrayList<>();
        Set<Long> usedDropIds = new HashSet<>();
        for (int i = 0; i < memories.size(); i++) {
            for (int j = i + 1; j < memories.size(); j++) {
                AssistantUserMemory a = memories.get(i);
                AssistantUserMemory b = memories.get(j);
                String keyA = StrUtil.blankToDefault(a.getMemoryKey(), "");
                String keyB = StrUtil.blankToDefault(b.getMemoryKey(), "");
                if (StrUtil.isBlank(keyA) || StrUtil.isBlank(keyB)
                        || StrUtil.equals(a.getMemoryValue(), b.getMemoryValue())) {
                    continue;
                }
                double similarity = SimilarityUtil.textSimilarity(keyA, keyB);
                if (similarity < threshold) {
                    continue;
                }
                AssistantUserMemory keep = newer(a, b);
                AssistantUserMemory drop = keep == a ? b : a;
                if (usedDropIds.contains(drop.getId())) {
                    continue;
                }
                usedDropIds.add(drop.getId());
                conflicts.add(new Conflict(keep.getId(), keep.getMemoryKey(), keep.getMemoryValue(),
                        drop.getId(), drop.getMemoryKey(), drop.getMemoryValue(),
                        Math.round(similarity * 100D) / 100D));
                if (conflicts.size() >= MAX_CONFLICTS) {
                    return conflicts;
                }
            }
        }
        return conflicts;
    }

    private AssistantUserMemory newer(AssistantUserMemory a, AssistantUserMemory b) {
        LocalDateTime timeA = effectiveTime(a);
        LocalDateTime timeB = effectiveTime(b);
        if (timeA == null && timeB == null) {
            return a.getId() > b.getId() ? a : b;
        }
        if (timeA == null) {
            return b;
        }
        if (timeB == null) {
            return a;
        }
        return timeA.isAfter(timeB) ? a : b;
    }

    private LocalDateTime effectiveTime(AssistantUserMemory memory) {
        return memory.getUpdateTime() != null ? memory.getUpdateTime() : memory.getCreateTime();
    }

    private Fact toFact(AssistantUserMemory memory) {
        LocalDateTime time = effectiveTime(memory);
        long ageDays = time == null ? 0L : Math.max(0L, Duration.between(time, LocalDateTime.now()).toDays());
        return new Fact(memory.getId(), memory.getMemoryKey(), memory.getMemoryValue(),
                ageDays, memory.getHitCount() == null ? 0 : memory.getHitCount());
    }

    private List<AssistantUserMemory> scan() {
        String tenantId = TenantContextUtil.requireTenantId();
        String userId = TenantContextUtil.requireUserId();
        return memoryMapper.selectList(new LambdaQueryWrapper<AssistantUserMemory>()
                .eq(AssistantUserMemory::getTenantId, tenantId)
                .eq(AssistantUserMemory::getUserId, userId)
                .orderByDesc(AssistantUserMemory::getUpdateTime)
                .last("limit " + MAX_SCAN));
    }
}
