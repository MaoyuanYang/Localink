package com.localink.service;

import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.vo.SeckillVoucherVO;

import java.util.List;

public interface SeckillVoucherService {

    String create(SeckillVoucherDTO dto);

    void update(SeckillVoucherDTO dto);

    void delete(Long voucherId);

    SeckillVoucherVO detail(Long voucherId);

    List<SeckillVoucherVO> listByShop(Long shopId);
}
