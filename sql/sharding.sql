-- M4.3/M4.4 分片改造脚本（可重放）：开发态同实例两 schema 逻辑双库
-- ds0 = 现有库 localink（其余 11 表不动，订单域两表拆 _0/_1）
-- ds1 = 新建库 localink_1（仅订单域分片表 _0/_1）
-- 规则：库 = user_id % 2，表 = voucher_id % 2；非分片表走 SINGLE 规则落 ds0
-- 订单 ID 自 M4.4 起由 id-starter 雪花生成（应用侧）

CREATE DATABASE IF NOT EXISTS `localink_1` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ============ 券订单表（4 份：2 库 × 2 表） ============
DROP TABLE IF EXISTS `localink`.`lk_voucher_order`;
DROP TABLE IF EXISTS `localink`.`lk_voucher_order_0`;
DROP TABLE IF EXISTS `localink`.`lk_voucher_order_1`;
DROP TABLE IF EXISTS `localink_1`.`lk_voucher_order_0`;
DROP TABLE IF EXISTS `localink_1`.`lk_voucher_order_1`;

CREATE TABLE `localink`.`lk_voucher_order_0` (
    `id`                    bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `user_id`               bigint unsigned NOT NULL COMMENT '下单用户（库分片键）',
    `voucher_id`            bigint unsigned NOT NULL COMMENT '购买的券（表分片键）',
    `voucher_type`          tinyint unsigned NOT NULL DEFAULT 1 COMMENT '券类型冗余（1普通/2秒杀）',
    `status`                tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1已创建/2用户取消/3超时关闭',
    `reconciliation_status` tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1待处理/2异常/3不一致/4一致',
    `create_time`           datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `close_time`            datetime                 DEFAULT NULL COMMENT '关闭时间',
    `update_time`           datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `active_flag`           tinyint unsigned GENERATED ALWAYS AS (IF(voucher_type = 2 AND status = 1, 1, NULL)) VIRTUAL COMMENT '秒杀活跃订单标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_voucher_active` (`user_id`, `voucher_id`, `active_flag`),
    KEY `idx_voucher_id` (`voucher_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='券订单表（分片 _0）';

CREATE TABLE `localink`.`lk_voucher_order_1` LIKE `localink`.`lk_voucher_order_0`;
CREATE TABLE `localink_1`.`lk_voucher_order_0` LIKE `localink`.`lk_voucher_order_0`;
CREATE TABLE `localink_1`.`lk_voucher_order_1` LIKE `localink`.`lk_voucher_order_0`;

-- ============ 对账流水表（4 份，随订单域同规则） ============
DROP TABLE IF EXISTS `localink`.`lk_voucher_reconcile_log`;
DROP TABLE IF EXISTS `localink`.`lk_voucher_reconcile_log_0`;
DROP TABLE IF EXISTS `localink`.`lk_voucher_reconcile_log_1`;
DROP TABLE IF EXISTS `localink_1`.`lk_voucher_reconcile_log_0`;
DROP TABLE IF EXISTS `localink_1`.`lk_voucher_reconcile_log_1`;

CREATE TABLE `localink`.`lk_voucher_reconcile_log_0` (
    `id`                    bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `order_id`              bigint unsigned NOT NULL COMMENT '订单ID',
    `user_id`               bigint unsigned NOT NULL COMMENT '下单用户（库分片键）',
    `voucher_id`            bigint unsigned NOT NULL COMMENT '券ID（表分片键）',
    `trace_id`              bigint unsigned NOT NULL COMMENT '链路追踪ID（资格生命周期ID）',
    `message_id`            varchar(64)              DEFAULT NULL COMMENT 'Kafka消息UUID',
    `log_type`              tinyint         NOT NULL DEFAULT 1 COMMENT '1扣减/2恢复',
    `business_type`         tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1下单成功/2下单超时/3下单失败',
    `before_qty`            int                      DEFAULT NULL COMMENT '变动前库存',
    `change_qty`            int                      DEFAULT NULL COMMENT '变动数量',
    `after_qty`             int                      DEFAULT NULL COMMENT '变动后库存',
    `reconciliation_status` tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1待处理/2异常/3不一致/4一致',
    `detail`                varchar(1024)            DEFAULT NULL COMMENT '差异说明',
    `create_time`           datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_log` (`order_id`, `log_type`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_message_id` (`message_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='对账流水表（分片 _0）';

CREATE TABLE `localink`.`lk_voucher_reconcile_log_1` LIKE `localink`.`lk_voucher_reconcile_log_0`;
CREATE TABLE `localink_1`.`lk_voucher_reconcile_log_0` LIKE `localink`.`lk_voucher_reconcile_log_0`;
CREATE TABLE `localink_1`.`lk_voucher_reconcile_log_1` LIKE `localink`.`lk_voucher_reconcile_log_0`;

-- ============ M4.5 订单路由表（非分片，落 ds0；orderId 反查分片位置） ============
DROP TABLE IF EXISTS `localink`.`lk_order_route`;

CREATE TABLE `localink`.`lk_order_route` (
    `id`          bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `order_id`    bigint unsigned NOT NULL COMMENT '订单ID（对外 orderId）',
    `user_id`     bigint unsigned NOT NULL COMMENT '下单用户（库定位键）',
    `voucher_id`  bigint unsigned NOT NULL COMMENT '券ID（表定位键）',
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单路由表（orderId 反查分片位置）';
