package com.localink.service;

/**
 * 帖子异步审核（M6-F）：先发后审档——同步初筛通过的帖已可见，此处用隐性词库复审，
 * 命中则收回（audit=2 + 复用 PostDeletedEvent 删 ES 等派生投影）。
 */
public interface PostAuditService {

    /**
     * 复审一帖（消费审核消息调用）。
     *
     * @return true=已驳回（audit 置 2 且发布撤下事件）；false=复审通过或无需处理
     *         （帖不存在/已驳回——重复消息幂等）
     */
    boolean rejectIfRisky(Long postId);
}
