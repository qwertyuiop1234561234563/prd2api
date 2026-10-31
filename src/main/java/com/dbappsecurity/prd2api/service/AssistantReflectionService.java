package com.dbappsecurity.prd2api.service;

/**
 * 失败反思（Reflexion）
 * <p>
 * 工具失败后不盲目重试，而是先诊断“为什么失败、下一步该改什么”，
 * 把结论回灌给模型并沉淀成经验，避免同类失败反复出现。
 */
public interface AssistantReflectionService {

    /**
     * 对一次工具失败做反思
     *
     * @param toolName  工具名
     * @param arguments 调用参数
     * @param error     工具返回的错误信息
     * @return 反思结论（诊断 + 建议）；无可用信息时返回 null
     */
    Lesson reflect(String toolName, String arguments, String error);

    /**
     * 反思结论
     *
     * @param diagnosis  失败原因
     * @param suggestion 下一步建议
     * @param reused     是否命中了历史同类失败的教训
     */
    record Lesson(String diagnosis, String suggestion, boolean reused) {

        public String text() {
            return "原因：" + diagnosis + "；下一步：" + suggestion;
        }
    }
}
