package com.dbappsecurity.prd2api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 隔离线程池：文件 IO 线程池、LLM 调用线程池
 * <p>
 * 两类任务资源互相隔离，避免 LLM 长耗时拖垮文件处理。
 */
@Slf4j
@Configuration
public class AsyncThreadPoolConfig {

    public static final String FILE_EXECUTOR = "fileExecutor";
    public static final String LLM_EXECUTOR = "llmExecutor";
    public static final String TOOL_EXECUTOR = "toolExecutor";

    @Bean(FILE_EXECUTOR)
    public ThreadPoolTaskExecutor fileExecutor(Prd2ApiProperties properties) {
        Prd2ApiProperties.Async async = properties.getAsync();
        // 文件 IO 耗时短，队列满时由调用线程兜底执行即可
        return buildExecutor("file-io-", async.getFileCore(), async.getFileMax(), async.getFileQueue(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(LLM_EXECUTOR)
    public ThreadPoolTaskExecutor llmExecutor(Prd2ApiProperties properties) {
        Prd2ApiProperties.Async async = properties.getAsync();
        // LLM 流水线动辄数分钟，绝不能回落到 Web 请求线程执行，队列满直接拒绝并由调用方标记任务失败
        return buildExecutor("llm-call-", async.getLlmCore(), async.getLlmMax(), async.getLlmQueue(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(TOOL_EXECUTOR)
    public ThreadPoolTaskExecutor toolExecutor(Prd2ApiProperties properties) {
        Prd2ApiProperties.Agent agent = properties.getAgent();
        int core = Math.max(2, agent.getToolCore());
        // 工具多为 DB 查询，允许少量堆积；队列满时由调用线程兜底，避免直接丢掉工具结果
        return buildExecutor("agent-tool-", core, core * 2, Math.max(64, core * 16),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private ThreadPoolTaskExecutor buildExecutor(String prefix, int core, int max, int queue,
                                                 RejectedExecutionHandler handler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, core));
        executor.setMaxPoolSize(Math.max(Math.max(1, core), max));
        executor.setQueueCapacity(Math.max(1, queue));
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(handler);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("线程池初始化完成 prefix={} core={} max={} queue={}", prefix, core, max, queue);
        return executor;
    }
}
