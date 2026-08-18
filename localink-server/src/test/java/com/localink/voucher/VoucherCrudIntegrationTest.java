package com.localink.voucher;

import com.localink.api.dto.VoucherDTO;
import com.localink.api.vo.VoucherVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.mapper.VoucherMapper;
import com.localink.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class VoucherCrudIntegrationTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private VoucherMapper voucherMapper;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(voucherMapper::deleteById);
    }

    private VoucherDTO newDto() {
        VoucherDTO dto = new VoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("测试普通券-" + System.nanoTime());
        dto.setSubTitle("副标题");
        dto.setRules("满100可用");
        dto.setPayValue(0L);
        dto.setActualValue(5000L);
        return dto;
    }

    @Test
    void createSetsTypeNormalAndDefaultStatus() {
        VoucherDTO dto = newDto();
        Long id = Long.valueOf(voucherService.create(dto));
        createdIds.add(id);

        VoucherVO vo = voucherService.detail(id);
        assertEquals(dto.getTitle(), vo.getTitle());
        assertEquals(1, vo.getType());
        assertEquals(1, vo.getStatus());
        assertEquals(0L, vo.getPayValue());
        assertEquals(5000L, vo.getActualValue());
    }

    @Test
    void listReturnsOnlyOnShelf() {
        VoucherDTO dto = newDto();
        Long id = Long.valueOf(voucherService.create(dto));
        createdIds.add(id);

        assertTrue(voucherService.listByShop(1L).stream().anyMatch(v -> v.getId().equals(id)));

        dto.setId(id);
        dto.setStatus(2);
        voucherService.update(dto);

        assertFalse(voucherService.listByShop(1L).stream().anyMatch(v -> v.getId().equals(id)));
        assertEquals(2, voucherService.detail(id).getStatus());
    }

    @Test
    void deleteRemovesVoucher() {
        Long id = Long.valueOf(voucherService.create(newDto()));
        createdIds.add(id);

        voucherService.delete(id);
        createdIds.remove(id);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherService.detail(id));
        assertEquals(BaseCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateWithoutIdRejected() {
        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherService.update(newDto()));
        assertEquals(BaseCode.PARAM_ERROR.getCode(), ex.getCode());
    }
}
