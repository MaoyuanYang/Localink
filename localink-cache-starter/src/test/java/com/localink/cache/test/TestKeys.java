package com.localink.cache.test;

import com.localink.cache.KeyTemplate;

/**
 * 框架测试用 key 模板 fixture（框架层不含业务 key，测试自带枚举验证 KeyTemplate 扩展点）。
 */
enum TestKeys implements KeyTemplate {

    STRING("test:m22:string"),

    STRING_TTL("test:m22:string:ttl"),

    HASH("test:m22:hash"),

    SET_A("test:m22:set:a"),

    SET_B("test:m22:set:b"),

    ZSET("test:m22:zset"),

    PAIR("test:m22:pair:%s:%s"),

    HASH_TAG("test:m22:tag:{%s}");

    private final String template;

    TestKeys(String template) {
        this.template = template;
    }

    @Override
    public String template() {
        return template;
    }
}
