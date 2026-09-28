package com.localink.mq;

/**
 * 帖子→ES 同步事件类型（M6-D）：消费端按类型分派 upsert/删文档。
 */
public enum PostSyncEvent {

    /** 帖子创建/可见（audit=1）：查 DB 组装文档 upsert（天然幂等，后到覆盖）。 */
    UPSERT,

    /** 帖子删除：按 id 删 ES 文档（M6-F 审核驳回复用同一事件）。 */
    DELETE
}
