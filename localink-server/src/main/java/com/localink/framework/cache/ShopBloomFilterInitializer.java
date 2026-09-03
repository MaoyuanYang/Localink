package com.localink.framework.cache;

import com.localink.cache.BloomFilterRegistry;
import com.localink.constant.BloomFilterAlias;
import com.localink.entity.Shop;
import com.localink.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商户布隆过滤器启动灌数据：全量 lk_shop id 灌入过滤器。
 * 位图持久在 Redis，日常重启数据仍在，重灌是对账兜底（幂等，已存在元素 add 无副作用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopBloomFilterInitializer implements ApplicationRunner {

    private final ShopMapper shopMapper;
    private final BloomFilterRegistry bloomFilterRegistry;

    @Override
    public void run(ApplicationArguments args) {
        List<Shop> shops = shopMapper.selectList(null);
        shops.forEach(shop -> bloomFilterRegistry.add(BloomFilterAlias.SHOP, String.valueOf(shop.getId())));
        log.info("商户布隆过滤器灌入完成, count={}", shops.size());
    }
}
