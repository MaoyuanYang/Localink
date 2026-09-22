package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.OrderRoute;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.seckill.SeckillTokenService;
import com.localink.mapper.OrderRouteMapper;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.VoucherOrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4.4/M4.5 分片路由验证：奇/偶 userId 落不同库（user_id%2）、voucherId 落对应表（voucher_id%2）、
 * 路由表随建单同写、locateOrder 反查位置与物理表实际数据吻合。物理断言用 DriverManager 直查
 * 两库四表（不经过逻辑源，避免"用被测物验证被测物"）。
 */
@SpringBootTest
class ShardingRouteIntegrationTest {

    private static final long ODD_USER_ID = 9201L;
    private static final long EVEN_USER_ID = 9202L;
    private static final String JDBC = "jdbc:mysql://localhost:3306/%s?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "localink123";

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherOrderService voucherOrderService;

    @Autowired
    private SeckillTokenService seckillTokenService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private com.localink.mapper.VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private com.localink.mapper.VoucherReconcileLogMapper voucherReconcileLogMapper;

    @Autowired
    private OrderRouteMapper orderRouteMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private SeckillStockCache seckillStockCache;

    private final List<Long> createdVoucherIds = new ArrayList<>();

    @BeforeEach
    void insertUsers() {
        insertUser(ODD_USER_ID, "13800009201");
        insertUser(EVEN_USER_ID, "13800009202");
    }

    @AfterEach
    void cleanup() throws Exception {
        UserHolder.clear();
        for (Long voucherId : createdVoucherIds) {
            int tableIndex = (int) Math.floorMod(voucherId, 2);
            exec("localink", "DELETE FROM lk_voucher_order_" + tableIndex + " WHERE voucher_id=" + voucherId);
            exec("localink_1", "DELETE FROM lk_voucher_order_" + tableIndex + " WHERE voucher_id=" + voucherId);
            exec("localink", "DELETE FROM lk_voucher_reconcile_log_" + tableIndex + " WHERE voucher_id=" + voucherId);
            exec("localink_1", "DELETE FROM lk_voucher_reconcile_log_" + tableIndex + " WHERE voucher_id=" + voucherId);
            orderRouteMapper.delete(new LambdaQueryWrapper<OrderRoute>()
                    .eq(OrderRoute::getVoucherId, voucherId));
            seckillStockCache.evict(voucherId);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, voucherId));
            voucherMapper.deleteById(voucherId);
        }
        userMapper.deleteById(ODD_USER_ID);
        userMapper.deleteById(EVEN_USER_ID);
    }

    @Test
    void ordersLandOnShardsByUserAndVoucherKeys() throws Exception {
        Long voucherId = createVoucher(10);
        int tableIndex = (int) Math.floorMod(voucherId, 2);
        String oddOrderId = orderAs(ODD_USER_ID, voucherId);
        String evenOrderId = orderAs(EVEN_USER_ID, voucherId);

        com.localink.entity.VoucherOrder oddOrder =
                OrderAwait.awaitById(voucherOrderMapper, Long.valueOf(oddOrderId));
        com.localink.entity.VoucherOrder evenOrder =
                OrderAwait.awaitById(voucherOrderMapper, Long.valueOf(evenOrderId));
        assertNotNull(oddOrder);
        assertNotNull(evenOrder);

        assertEquals(1, count("localink_1", "lk_voucher_order_" + tableIndex, Long.valueOf(oddOrderId)),
                "奇数 userId 应落 ds_1（user_id%2=1）");
        assertEquals(1, count("localink", "lk_voucher_order_" + tableIndex, Long.valueOf(evenOrderId)),
                "偶数 userId 应落 ds_0（user_id%2=0）");

        VoucherOrderService.OrderLocation oddLocation = voucherOrderService.locateOrder(Long.valueOf(oddOrderId));
        assertEquals("ds_1", oddLocation.dataSource());
        assertEquals("lk_voucher_order_" + tableIndex, oddLocation.physicalTable());
        assertTrue(oddLocation.orderExists());
        assertEquals(ODD_USER_ID, oddLocation.userId());

        VoucherOrderService.OrderLocation evenLocation = voucherOrderService.locateOrder(Long.valueOf(evenOrderId));
        assertEquals("ds_0", evenLocation.dataSource());
        assertTrue(evenLocation.orderExists());
    }

    @Test
    void nonShardedTablesStayOnDefaultDataSource() {
        User odd = userMapper.selectById(ODD_USER_ID);
        assertNotNull(odd, "非分片表（用户）经逻辑源读应正常路由 ds_0");
        Long routeCount = orderRouteMapper.selectCount(new LambdaQueryWrapper<OrderRoute>()
                .eq(OrderRoute::getVoucherId, -1L));
        assertEquals(0L, routeCount, "路由表（非分片）读写正常");
    }

    private String orderAs(Long userId, Long voucherId) {
        UserDTO holder = new UserDTO();
        holder.setId(userId);
        holder.setLevel(0);
        UserHolder.set(holder);
        String token = seckillTokenService.issue(voucherId, userId);
        return voucherOrderService.seckill(voucherId, token);
    }

    private void insertUser(Long id, String phone) {
        User user = new User();
        user.setId(id);
        user.setPhone(phone);
        user.setLevel(0);
        userMapper.insert(user);
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M4分片券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(stock);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().minusHours(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusHours(2).withNano(0));
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);
        return voucherId;
    }

    private int count(String schema, String table, Long orderId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                String.format(JDBC, schema), DB_USER, DB_PASS);
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE id=" + orderId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void exec(String schema, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                String.format(JDBC, schema), DB_USER, DB_PASS);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
