package com.localink.cache.json;

import com.alibaba.fastjson2.JSON;

import java.util.List;

/**
 * Redis 值序列化编解码器：String 原样读写，其余对象经 fastjson2 JSON 化。
 */
public final class RedisJsonCodec {

    private RedisJsonCodec() {
    }

    /**
     * 写入侧：null 返回 null；String 原样返回；其余对象转 JSON 字符串。
     */
    public static String serialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return JSON.toJSONString(value);
    }

    /**
     * 读取侧：raw 为 null 返回 null；目标类型为 String 时原样返回；其余按 JSON 反序列化。
     */
    public static <T> T deserialize(String raw, Class<T> type) {
        if (raw == null) {
            return null;
        }
        if (type == String.class) {
            return type.cast(raw);
        }
        return JSON.parseObject(raw, type);
    }

    /**
     * 读取侧：JSON 数组按元素类型反序列化；raw 为 null 返回 null。
     */
    public static <T> List<T> deserializeList(String raw, Class<T> elementType) {
        if (raw == null) {
            return null;
        }
        return JSON.parseArray(raw, elementType);
    }
}
