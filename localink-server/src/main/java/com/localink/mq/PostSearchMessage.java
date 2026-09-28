package com.localink.mq;

/**
 * 帖子搜索同步消息（M6-D）：只带 postId + 事件类型——消息瘦身（帖字段一改不必升消息版），
 * 消费端查事实源组装，永远拿到最新态；upsert 后到覆盖=天然幂等。
 * key=postId：同帖 UPSERT/DELETE 同分区 FIFO，跨帖乱序无害（文档互相独立）。
 */
public record PostSearchMessage(Long postId, PostSyncEvent event) {
}
