package com.localink.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopNearbyVO;
import com.localink.api.vo.ShopVO;

public interface ShopService {

    ShopVO detail(Long id);

    Page<ShopVO> page(Long typeId, long page, long size);

    String create(ShopDTO dto);

    void update(ShopDTO dto);

    void delete(Long id);

    /**
     * 附近商户（M6-F）：GEO 按距离升序圈选（半径内取前 count），回填商户信息与距离（米）。
     */
    java.util.List<ShopNearbyVO> nearby(double longitude, double latitude,
                                        double radiusMeters, int count);
}
