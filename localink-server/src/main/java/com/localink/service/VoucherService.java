package com.localink.service;

import com.localink.api.dto.VoucherDTO;
import com.localink.api.vo.VoucherVO;

import java.util.List;

public interface VoucherService {

    List<VoucherVO> listByShop(Long shopId);

    VoucherVO detail(Long id);

    String create(VoucherDTO dto);

    void update(VoucherDTO dto);

    void delete(Long id);

    String claim(Long voucherId);
}
