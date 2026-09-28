package com.localink.event;

/**
 * 帖子已删除事件（M6-D）：与 PostCreatedEvent 对偶，ES 删文档等派生投影订阅
 * （M6-F 审核驳回时复用同款 DELETE 同步）。
 */
public record PostDeletedEvent(Long postId) {
}
