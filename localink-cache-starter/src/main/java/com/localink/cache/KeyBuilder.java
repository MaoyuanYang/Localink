package com.localink.cache;

/**
 * Key 治理入口：按模板 + 参数生成带统一前缀的 {@link KeyBuild}。
 */
public class KeyBuilder {

    private final String keyPrefix;

    public KeyBuilder(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /**
     * 生成完整 key。
     *
     * @param keyTemplate key 模板枚举
     * @param args        模板占位符参数（按 %s 顺序）
     */
    public KeyBuild build(KeyTemplate keyTemplate, Object... args) {
        return new KeyBuild(keyPrefix + String.format(keyTemplate.template(), args));
    }
}
