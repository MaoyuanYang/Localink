package com.localink.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;

public interface ShopService {

    ShopVO detail(Long id);

    Page<ShopVO> page(Long typeId, long page, long size);

    String create(ShopDTO dto);

    void update(ShopDTO dto);

    void delete(Long id);
}
