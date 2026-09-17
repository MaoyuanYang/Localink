package com.localink.idempotent;

/**
 * 幂等标记命中时的处置策略。
 */
public enum DuplicatePolicy {

    /**
     * 静默跳过：void 返回 null；有返回值回放已存结果。适用消费端重复投递（跳过后正常 ack）。
     */
    SKIP,

    /**
     * 拒绝重复：抛 IDEMPOTENT_DUPLICATE("请勿重复提交")。适用交互端防双击。
     */
    REJECT
}
