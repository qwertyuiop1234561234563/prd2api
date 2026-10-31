package com.dbappsecurity.prd2api.service;

import com.dbappsecurity.prd2api.vo.AgentStepVO;

import java.util.List;

/**
 * 答复溯源服务
 * <p>
 * 解决的问题：证据链只说明“查到了什么”，不说明“答复到底用没用这些证据”。
 * 这里把每条证据在答复文本中的位置（字符区间）算出来，并给出引用覆盖率，
 * 用于量化“有据可依”的比例，暴露无依据的结论。
 */
public interface AssistantProvenanceService {

    /**
     * 计算答复对证据的引用情况
     */
    Report trace(String reply, List<AgentStepVO> steps);

    /**
     * 单条证据的溯源锚点，replyStart/replyEnd 为在答复中的字符区间（未命中为 -1）
     */
    record Anchor(int stepNo, String type, String source, String chunkId,
                  int replyStart, int replyEnd, double overlap) {

        public boolean verified() {
            return replyStart >= 0;
        }
    }

    /**
     * 溯源报告：覆盖率 = 被答复实际引用的证据数 / 可用证据数
     */
    record Report(double coverage, int verified, int total, List<Anchor> anchors) {
    }
}
