package com.localink.cache;

/**
 * Key 模板定义：业务方以枚举实现本接口，交由 {@link KeyBuilder} 生成完整 key。
 */
public interface KeyTemplate {

    /**
     * key 模板，占位符用 %s（例：sms:code:%s）；需要集群同槽位时写 {%s} hash tag。
     */
    String template();
}
