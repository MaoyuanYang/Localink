package com.localink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localink.entity.VoucherOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 条件关单：仅"已创建"状态可关——affected=0 即已关/已取消/不存在，关单幂等闸门。
     */
    @Update("UPDATE lk_voucher_order SET status = 3, close_time = NOW() WHERE id = #{orderId} AND status = 1")
    int closeIfCreated(@Param("orderId") Long orderId);
}
