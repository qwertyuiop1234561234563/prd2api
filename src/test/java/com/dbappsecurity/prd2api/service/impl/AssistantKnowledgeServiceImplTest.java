package com.dbappsecurity.prd2api.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.KnowledgeDocStatus;
import com.dbappsecurity.prd2api.dto.KnowledgeDocQueryDTO;
import com.dbappsecurity.prd2api.entity.AssistantKnowledgeDoc;
import com.dbappsecurity.prd2api.mapper.AssistantKnowledgeChunkMapper;
import com.dbappsecurity.prd2api.mapper.AssistantKnowledgeDocMapper;
import com.dbappsecurity.prd2api.rag.ChromaVectorService;
import com.dbappsecurity.prd2api.rag.EmbeddingService;
import com.dbappsecurity.prd2api.rag.KnowledgeBaseService;
import com.dbappsecurity.prd2api.service.QuotaResolver;
import com.dbappsecurity.prd2api.util.RateLimitUtil;
import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.dbappsecurity.prd2api.vo.KnowledgeDocVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库状态机与权限边界测试：管理操作仅 ADMIN 可用，运行中禁止启停/重建/删除，分页状态过滤与 VO 映射
 */
@ExtendWith(MockitoExtension.class)
class AssistantKnowledgeServiceImplTest {

    @Mock
    private AssistantKnowledgeDocMapper docMapper;

    @Mock
    private AssistantKnowledgeChunkMapper chunkMapper;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChromaVectorService chromaVectorService;

    @Mock
    private RateLimitUtil rateLimitUtil;

    @Mock
    private QuotaResolver quotaResolver;

    @Mock
    private ThreadPoolTaskExecutor fileExecutor;

    private AssistantKnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssistantKnowledgeServiceImpl(docMapper, chunkMapper, knowledgeBaseService,
                embeddingService, chromaVectorService, rateLimitUtil, quotaResolver,
                new Prd2ApiProperties(), fileExecutor);
        TenantContextUtil.set("u1", "t1", "alice", TenantContextUtil.ADMIN_ROLE);
    }

    @AfterEach
    void tearDown() {
        TenantContextUtil.clear();
    }

    @Test
    void shouldRejectToggleWhileIndexing() {
        when(docMapper.selectById("d1")).thenReturn(doc("d1", "u1", KnowledgeDocStatus.PARSING));

        assertThrows(BizException.class, () -> service.setEnabled("d1", false));
        verify(docMapper, never()).update(any(), any());
    }

    @Test
    void shouldRejectDeleteWhileIndexing() {
        when(docMapper.selectById("d1")).thenReturn(doc("d1", "u1", KnowledgeDocStatus.EMBEDDING));

        assertThrows(BizException.class, () -> service.delete("d1"));
        verify(chunkMapper, never()).selectList(any());
        verify(docMapper, never()).deleteById("d1");
    }

    @Test
    void shouldRejectNonAdminDelete() {
        // 知识库管理仅限管理员，上传人本人也不能删除
        TenantContextUtil.set("u1", "t1", "alice", TenantContextUtil.USER_ROLE);

        assertThrows(BizException.class, () -> service.delete("d1"));
        verify(chunkMapper, never()).selectList(any());
        verify(docMapper, never()).deleteById("d1");
    }

    @Test
    void shouldRejectUnknownStatusFilter() {
        KnowledgeDocQueryDTO dto = new KnowledgeDocQueryDTO();
        dto.setStatus("UNKNOWN");

        assertThrows(BizException.class, () -> service.page(dto));
        verify(docMapper, never()).selectPage(any(), any());
    }

    @Test
    void shouldMapPageRecords() {
        AssistantKnowledgeDoc doc = doc("d1", "u1", KnowledgeDocStatus.COMPLETED);
        doc.setChunkCount(12);
        doc.setEnabled(1);
        doc.setVectorIndexed(1);
        Page<AssistantKnowledgeDoc> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(doc));
        when(docMapper.selectPage(any(), any())).thenReturn(page);

        Page<KnowledgeDocVO> result = service.page(new KnowledgeDocQueryDTO());

        assertEquals(1, result.getTotal());
        KnowledgeDocVO vo = result.getRecords().get(0);
        assertEquals("d1", vo.getDocId());
        assertEquals(12, vo.getChunkCount());
        assertTrue(vo.isEnabled());
        assertTrue(vo.isVectorIndexed());
    }

    private AssistantKnowledgeDoc doc(String docId, String createBy, KnowledgeDocStatus status) {
        AssistantKnowledgeDoc doc = new AssistantKnowledgeDoc();
        doc.setDocId(docId);
        doc.setTenantId("t1");
        doc.setDocName("需求说明.md");
        doc.setFileSuffix("md");
        doc.setChunkStrategy("semantic");
        doc.setStatus(status.name());
        doc.setEnabled(1);
        doc.setVectorIndexed(0);
        doc.setCreateBy(createBy);
        doc.setCreateTime(LocalDateTime.now());
        return doc;
    }
}
