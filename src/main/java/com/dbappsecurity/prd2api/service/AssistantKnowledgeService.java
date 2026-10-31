package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.KnowledgeDocQueryDTO;
import com.dbappsecurity.prd2api.vo.KnowledgeChunkVO;
import com.dbappsecurity.prd2api.vo.KnowledgeDocVO;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 助手知识库：离线文档上传、解析切分、向量化与管理
 */
public interface AssistantKnowledgeService {

    /**
     * 上传知识文档并异步建索引
     *
     * @param strategy 切分策略 fixed / semantic，留空取全局配置
     * @return 文档信息，duplicated=true 表示租户内 md5 已存在，直接复用
     */
    KnowledgeDocVO upload(MultipartFile file, String strategy);

    /**
     * 分页查询当前租户知识文档
     */
    Page<KnowledgeDocVO> page(KnowledgeDocQueryDTO dto);

    /**
     * 分页预览文档分段
     */
    Page<KnowledgeChunkVO> pageChunks(String docId, int pageNum, int pageSize);

    /**
     * 启用 / 停用检索；停用会移除向量，启用时按需重建
     */
    void setEnabled(String docId, boolean enabled);

    /**
     * 重新解析、切分与向量化
     */
    void rebuild(String docId);

    /**
     * 删除文档、分段、向量与本地文件
     */
    void delete(String docId);

    /**
     * 知识库概览：按状态计数与分段总数
     */
    Map<String, Object> stats();

    /**
     * 检索测试：与 Agent 使用同一检索链路
     */
    List<RagChunkVO> search(String query, int topK);
}
