package com.localink.config;

import com.localink.api.vo.ShopVO;
import com.localink.cache.LocalCache;
import com.localink.cache.LocalCacheRegistry;
import com.localink.constant.LocalCacheAlias;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务本地缓存装配：从注册表按别名取类型化视图，供 Service 构造注入。
 */
@Configuration
public class LocalCacheConfig {

    @Bean
    public LocalCache<String, ShopVO> shopLocalCache(LocalCacheRegistry localCacheRegistry) {
        return localCacheRegistry.cache(LocalCacheAlias.SHOP);
    }
}
