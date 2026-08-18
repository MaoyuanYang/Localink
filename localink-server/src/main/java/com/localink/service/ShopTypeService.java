package com.localink.service;

import com.localink.api.dto.ShopTypeDTO;
import com.localink.api.vo.ShopTypeVO;

import java.util.List;

public interface ShopTypeService {

    List<ShopTypeVO> list();

    String create(ShopTypeDTO dto);

    void update(ShopTypeDTO dto);

    void delete(Long id);
}
