package com.localink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class SeckillScriptConfig {

    /**
     * M3.12 起返回字符串：'0|traceId|before|after' 成功（携账目回传建消息）/ 数字码失败。
     */
    @Bean
    public RedisScript<String> seckillDeductScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_deduct.lua")));
        script.setResultType(String.class);
        return script;
    }

    /**
     * 返回字符串：'0|before|after' 补偿完成（携账目落恢复流水行）/ '1' 无需补偿。
     */
    @Bean
    public RedisScript<String> seckillRollbackScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_rollback.lua")));
        script.setResultType(String.class);
        return script;
    }
}
