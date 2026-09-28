package com.localink.geo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.ShopDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.entity.User;
import com.localink.mapper.ShopMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.ShopService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-F GEO 验证：启动灌入（杭州种子坐标可检索）、nearby 按距离升序+半径过滤+count 截断、
 * 商户坐标更新覆盖写、删除移除。自建固定坐标店保证断言确定性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopGeoIntegrationTest {

    private static final String PHONE = "13900139621";
    /** 杭州一号种子店的坐标（sql/localink.sql 种子数据，启动灌入后可检索）。 */
    private static final double SEED_LON = 120.1632;
    private static final double SEED_LAT = 30.2745;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ShopService shopService;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<String> issuedTokens = new ArrayList<>();
    private final List<Long> createdShopIds = new ArrayList<>();
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        // 历史失败运行可能残留测试坐标盒内的脏行（DB+GEO）——先清，保证断言确定性
        var leftovers = shopMapper.selectList(new LambdaQueryWrapper<Shop>()
                .between(Shop::getLongitude, 119.99, 121.01)
                .between(Shop::getLatitude, 29.99, 30.01));
        leftovers.forEach(shop -> redisCache.geos().remove(
                keyBuilder.build(KeyManage.SHOP_GEO), String.valueOf(shop.getId())));
        shopMapper.delete(new LambdaQueryWrapper<Shop>()
                .between(Shop::getLongitude, 119.99, 121.01)
                .between(Shop::getLatitude, 29.99, 30.01));
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        token = userService.login(PHONE, code);
        issuedTokens.add(token);
    }

    @AfterEach
    void cleanup() {
        // 逐店兜底：一家删除失败不阻断其余清理（此前失败运行的残留即源于此）
        createdShopIds.forEach(id -> {
            try {
                shopService.delete(id);
            } catch (Exception ignored) {
            }
        });
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void startupSeedShopsAreSearchable() throws Exception {
        // 启动灌入：杭州种子店（坐标非 0）5km 内可检索到（宽松断言——不锁定具体 id 顺序）
        String resp = mockMvc.perform(get("/api/shop/nearby")
                        .queryParam("longitude", String.valueOf(SEED_LON))
                        .queryParam("latitude", String.valueOf(SEED_LAT))
                        .queryParam("radius", "5000").queryParam("count", "20"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var records = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONArray("data");
        org.junit.jupiter.api.Assertions.assertFalse(records.isEmpty(), "种子商户应被启动灌入");
    }

    @Test
    void nearbySortsByDistanceAndFiltersRadiusAndCount() throws Exception {
        Long center = createShop("圆心咖啡", 120.000, 30.000);
        Long near = createShop("近郊面馆", 120.010, 30.000);
        createShop("远郊农庄", 121.000, 30.000); // ~96km 外

        String resp = mockMvc.perform(get("/api/shop/nearby")
                        .queryParam("longitude", "120.000")
                        .queryParam("latitude", "30.000")
                        .queryParam("radius", "2000").queryParam("count", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var records = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONArray("data");
        org.junit.jupiter.api.Assertions.assertEquals(2, records.size(), "半径过滤掉 96km 外的店");
        org.junit.jupiter.api.Assertions.assertEquals(center.longValue(), records.getJSONObject(0).getLong("id"), "按距离升序");
        org.junit.jupiter.api.Assertions.assertEquals(near.longValue(), records.getJSONObject(1).getLong("id"));
        org.junit.jupiter.api.Assertions.assertEquals(0.0, records.getJSONObject(0).getDouble("distance"), 5.0);
        org.junit.jupiter.api.Assertions.assertEquals(960.0, records.getJSONObject(1).getDouble("distance"), 60.0);

        // count 截断
        String limited = mockMvc.perform(get("/api/shop/nearby")
                        .queryParam("longitude", "120.000")
                        .queryParam("latitude", "30.000")
                        .queryParam("radius", "2000").queryParam("count", "1"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(1,
                com.alibaba.fastjson2.JSON.parseObject(limited).getJSONArray("data").size());
    }

    @Test
    void updateOverwritesCoordinateInGeo() throws Exception {
        Long mover = createShop("搬家奶茶", 121.000, 30.000);
        ShopDTO dto = shopDto("搬家奶茶新址", 120.005, 30.000);
        dto.setId(mover);
        shopService.update(dto);

        String resp = mockMvc.perform(get("/api/shop/nearby")
                        .queryParam("longitude", "120.000")
                        .queryParam("latitude", "30.000")
                        .queryParam("radius", "2000").queryParam("count", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        boolean found = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONArray("data").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id"))
                .anyMatch(id -> id.equals(mover));
        org.junit.jupiter.api.Assertions.assertTrue(found, "坐标更新覆盖写后新址可检索");
    }

    @Test
    void deleteRemovesFromGeo() throws Exception {
        Long doomed = createShop("即将消失的店", 120.000, 30.000);
        shopService.delete(doomed);

        String resp = mockMvc.perform(get("/api/shop/nearby")
                        .queryParam("longitude", "120.000")
                        .queryParam("latitude", "30.000")
                        .queryParam("radius", "500").queryParam("count", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        boolean found = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONArray("data").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id"))
                .anyMatch(id -> id.equals(doomed));
        org.junit.jupiter.api.Assertions.assertFalse(found, "删店后 GEO 移除");
        createdShopIds.remove(doomed);
    }

    // ===== 工具 =====

    private Long createShop(String name, double lon, double lat) {
        ShopDTO dto = shopDto(name, lon, lat);
        return Long.valueOf(shopService.create(dto));
    }

    private ShopDTO shopDto(String name, double lon, double lat) {
        ShopDTO dto = new ShopDTO();
        dto.setName(name);
        dto.setTypeId(1L);
        dto.setImages("");
        dto.setAddress("测试地址-" + name);
        dto.setLongitude(lon);
        dto.setLatitude(lat);
        return dto;
    }
}
