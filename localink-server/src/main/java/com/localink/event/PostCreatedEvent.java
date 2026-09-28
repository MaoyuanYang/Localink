package com.localink.event;

/**
 * 帖子已创建事件（M6-C）：发帖是事实，Feed 收件箱是订阅该事实的派生投影——进程内事件解耦
 * （Post 不认识 Feed），真实粉丝量演进为 Kafka 异步推（M6-D 同模式跨进程版）。
 */
public record PostCreatedEvent(Long postId, Long authorId) {
}
