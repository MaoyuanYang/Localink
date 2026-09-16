package com.localink.cache;

import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Lua 脚本执行视图。多 key 脚本的 key 须落在同一哈希槽（KeyTemplate 的 {%s} hash tag），
 * Cluster 模式下跨槽执行会报错；单实例无此限制但按同槽设计可平滑演进。
 */
public interface RedisScriptOps {

    /**
     * 执行 Lua 脚本。
     *
     * @param script 脚本定义（由业务方以 RedisScript Bean 配置，脚本文件入 resources）
     * @param keys   脚本的 KEYS 入参（经 KeyBuild 治理）
     * @param args   脚本的 ARGV 入参（String/数字，由调用方保证与脚本约定一致）
     * @return 脚本返回值，类型由 RedisScript 的 resultType 决定
     */
    <T> T execute(RedisScript<T> script, List<KeyBuild> keys, Object... args);
}
