package com.localink.mq;

/**
 * 帖子异步审核消息（M6-F）：只带 postId——消费端查事实源复审，幂等。
 * key=postId：同帖审核与 ES 同步各自独立 topic，同 key 保分区有序。
 */
public record PostAuditMessage(Long postId) {
}
