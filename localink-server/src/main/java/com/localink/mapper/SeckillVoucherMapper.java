package com.localink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localink.entity.SeckillVoucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 扣减指定秒杀券库存，返回影响行数。
     */
    @Update("UPDATE lk_seckill_voucher SET stock = stock - 1 WHERE voucher_id = #{voucherId}")
    int deductStock(@Param("voucherId") Long voucherId);
}
