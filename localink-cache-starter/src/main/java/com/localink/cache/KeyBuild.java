package com.localink.cache;

import java.util.Objects;

/**
 * Redis key 值对象：仅能由 {@link KeyBuilder} 生成（构造器包私有），承载"治理过"的完整 key。
 */
public final class KeyBuild {

    private final String key;

    KeyBuild(String key) {
        this.key = key;
    }

    /**
     * 获取完整 key 字符串。
     */
    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return key.equals(((KeyBuild) o).key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        return key;
    }
}
