package com.localink.framework.shop;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商户 GEO 启动灌数据（M6-F，仿布隆初始化器先例）：全量 lk_shop 坐标灌入 GEO（坐标
 * 0/null 的脏行跳过——GEOADD 无意义）。GEOADD 幂等（重复灌覆盖同值），日常重启数据仍在，
 * 重灌是对账兜底；商户 CRUD 期间增量维护（ShopServiceImpl 三挂点）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopGeoInitializer implements ApplicationRunner {

    private final ShopMapper shopMapper;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    public void run(ApplicationArguments args) {
        List<Shop> shops = shopMapper.selectList(null);
        long count = shops.stream()
                .filter(this::hasCoordinate)
                .mapToLong(shop -> {
                    // 逐店容错：单条脏数据（历史越界坐标/Redis 抖动）只跳过自身，
                    // 绝不让 ApplicationRunner 失败拖垮整个应用启动
                    try {
                        redisCache.geos().add(keyBuilder.build(KeyManage.SHOP_GEO),
                                shop.getLongitude(), shop.getLatitude(), String.valueOf(shop.getId()));
                        return 1;
                    } catch (Exception e) {
                        log.error("商户 GEO 灌入失败(脏坐标行已跳过), shopId={}, lon={}, lat={}",
                                shop.getId(), shop.getLongitude(), shop.getLatitude(), e);
                        return 0;
                    }
                }).sum();
        log.info("商户 GEO 灌入完成, total={}, indexed={}", shops.size(), count);
    }

    private boolean hasCoordinate(Shop shop) {
        return shop.getLongitude() != null && shop.getLatitude() != null
                && (shop.getLongitude() != 0.0 || shop.getLatitude() != 0.0)
                && shop.getLongitude() >= -180.0 && shop.getLongitude() <= 180.0
                && shop.getLatitude() >= -90.0 && shop.getLatitude() <= 90.0;
    }
}
