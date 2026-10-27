package com.dbappsecurity.prd2api.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务进度流式推送服务
 */
public interface TaskStreamService {

    /**
     * 订阅任务进度，返回 SSE 连接；任务进入终态或超时后自动结束
     */
    SseEmitter stream(String taskId);
}
