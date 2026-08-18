package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.VoucherDTO;
import com.localink.api.vo.SeckillVoucherVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.Voucher;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SeckillVoucherCrudIntegrationTest {

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    private final List<Long> createdVoucherIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdVoucherIds.forEach(id -> {
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
    }

    private SeckillVoucherDTO newDto() {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("测试秒杀券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(50);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().plusDays(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusDays(2).withNano(0));
        return dto;
    }

    private SeckillVoucher selectSeckill(Long voucherId) {
        return seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
    }

    @Test
    void createWritesBothTables() {
        SeckillVoucherDTO dto = newDto();
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);

        Voucher voucher = voucherMapper.selectById(voucherId);
        assertEquals(2, voucher.getType());
        assertEquals(1, voucher.getStatus());

        SeckillVoucher seckill = selectSeckill(voucherId);
        assertEquals(50, seckill.getInitStock());
        assertEquals(50, seckill.getStock());
        assertEquals(0, seckill.getMinLevel());
        assertEquals(dto.getBeginTime(), seckill.getBeginTime());
        assertEquals(dto.getEndTime(), seckill.getEndTime());
    }

    @Test
    void detailAggregatesBothTables() {
        SeckillVoucherDTO dto = newDto();
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);

        SeckillVoucherVO vo = seckillVoucherService.detail(voucherId);
        assertEquals(voucherId, vo.getVoucherId());
        assertEquals(dto.getTitle(), vo.getTitle());
        assertEquals(50, vo.getStock());
        assertEquals(0, vo.getMinLevel());
        assertEquals(dto.getBeginTime(), vo.getBeginTime());
        assertEquals(dto.getEndTime(), vo.getEndTime());
    }

    @Test
    void updateChangesBothTablesButKeepsInitStock() {
        SeckillVoucherDTO dto = newDto();
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);

        dto.setId(voucherId);
        dto.setTitle(dto.getTitle() + "-改");
        dto.setStock(80);
        dto.setMinLevel(3);
        seckillVoucherService.update(dto);

        SeckillVoucherVO vo = seckillVoucherService.detail(voucherId);
        assertEquals(dto.getTitle(), vo.getTitle());
        assertEquals(80, vo.getStock());
        assertEquals(3, vo.getMinLevel());
        assertEquals(50, selectSeckill(voucherId).getInitStock());
    }

    @Test
    void deleteRemovesBothTables() {
        Long voucherId = Long.valueOf(seckillVoucherService.create(newDto()));
        createdVoucherIds.add(voucherId);

        seckillVoucherService.delete(voucherId);
        createdVoucherIds.remove(voucherId);

        assertNull(voucherMapper.selectById(voucherId));
        assertNull(selectSeckill(voucherId));
    }

    @Test
    void updateNormalVoucherRejected() {
        VoucherDTO normal = new VoucherDTO();
        normal.setShopId(1L);
        normal.setTitle("普通券-" + System.nanoTime());
        normal.setPayValue(0L);
        normal.setActualValue(100L);
        Long normalId = Long.valueOf(voucherService.create(normal));
        createdVoucherIds.add(normalId);

        SeckillVoucherDTO dto = newDto();
        dto.setId(normalId);
        LocalinkException ex = assertThrows(LocalinkException.class, () -> seckillVoucherService.update(dto));
        assertEquals(BaseCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void endTimeBeforeBeginTimeRejected() {
        SeckillVoucherDTO dto = newDto();
        dto.setEndTime(dto.getBeginTime().minusHours(1));
        LocalinkException ex = assertThrows(LocalinkException.class, () -> seckillVoucherService.create(dto));
        assertEquals(BaseCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void listReturnsOnlyOnShelfSeckill() {
        SeckillVoucherDTO dto = newDto();
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);

        assertTrue(seckillVoucherService.listByShop(1L).stream()
                .anyMatch(v -> v.getVoucherId().equals(voucherId)));

        dto.setId(voucherId);
        dto.setStatus(2);
        seckillVoucherService.update(dto);

        assertFalse(seckillVoucherService.listByShop(1L).stream()
                .anyMatch(v -> v.getVoucherId().equals(voucherId)));
    }
}
