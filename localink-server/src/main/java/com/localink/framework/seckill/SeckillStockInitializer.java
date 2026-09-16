package com.localink.framework.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.entity.SeckillVoucher;
import com.localink.mapper.SeckillVoucherMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动回灌秒杀库存：endTime 未到的券全量以 DB 当前值重写 Redis（幂等），
 * 兜住创建钩子之后 Redis 丢 key / 手工改库 / 重启等一切漂移。学 BloomInitializer 模式。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillStockInitializer implements ApplicationRunner {

    private final SeckillVoucherMapper seckillVoucherMapper;
    private final SeckillStockCache seckillStockCache;

    @Override
    public void run(ApplicationArguments args) {
        List<SeckillVoucher> upcoming = seckillVoucherMapper.selectList(
                new LambdaQueryWrapper<SeckillVoucher>()
                        .gt(SeckillVoucher::getEndTime, LocalDateTime.now()));
        upcoming.forEach(seckill ->
                seckillStockCache.warm(seckill.getVoucherId(), seckill.getStock(), seckill.getEndTime()));
        log.info("秒杀库存回灌完成, upcomingCount={}", upcoming.size());
    }
}
