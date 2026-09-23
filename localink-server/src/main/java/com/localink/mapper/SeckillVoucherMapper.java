package com.localink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localink.entity.SeckillVoucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 扣减指定秒杀券库存，返回影响行数。stock > 0 守卫由影响行数表达：0 即库存已空。
     */
    @Update("UPDATE lk_seckill_voucher SET stock = stock - 1 WHERE voucher_id = #{voucherId} AND stock > 0")
    int deductStock(@Param("voucherId") Long voucherId);

    /**
     * 逆增量回补库存（M5-B 关单）：与 Redis 侧逆增量同口径——不回源重建，只归还本次占用的 1。
     */
    @Update("UPDATE lk_seckill_voucher SET stock = stock + 1 WHERE voucher_id = #{voucherId}")
    int restoreStock(@Param("voucherId") Long voucherId);
}
