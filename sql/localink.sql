-- Localink 单库建表脚本（M1.2）
-- 设计依据：docs/database.md v1.0（12 张表）
-- 种子数据为虚构演示数据，仅供本地开发与演示使用
-- 执行方式：mysql -uroot -p < sql/localink.sql

CREATE DATABASE IF NOT EXISTS `localink`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `localink`;

-- -----------------------------------------------------------
-- 账户域
-- -----------------------------------------------------------

DROP TABLE IF EXISTS `lk_user`;
CREATE TABLE `lk_user` (
    `id`          bigint unsigned NOT NULL COMMENT '主键，用户ID（雪花）',
    `phone`       varchar(16)     NOT NULL COMMENT '手机号，登录唯一凭证',
    `password`    varchar(128)             DEFAULT NULL COMMENT '密码（加密存储），预留字段，当前仅验证码登录',
    `nick_name`   varchar(32)     NOT NULL DEFAULT '' COMMENT '昵称，注册默认用户{id}',
    `icon`        varchar(255)    NOT NULL DEFAULT '' COMMENT '头像URL',
    `city`        varchar(64)              DEFAULT '' COMMENT '城市',
    `introduce`   varchar(128)             DEFAULT NULL COMMENT '个人介绍',
    `gender`      tinyint unsigned         DEFAULT 0 COMMENT '性别 0未知/1男/2女',
    `birthday`    date                     DEFAULT NULL COMMENT '生日',
    `level`       tinyint unsigned NOT NULL DEFAULT 0 COMMENT '会员等级0~9，0未开通',
    `fans`        int unsigned    NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `followee`    int unsigned    NOT NULL DEFAULT 0 COMMENT '关注数',
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表';

-- -----------------------------------------------------------
-- 商户域
-- -----------------------------------------------------------

DROP TABLE IF EXISTS `lk_shop_type`;
CREATE TABLE `lk_shop_type` (
    `id`          bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `name`        varchar(32)     NOT NULL COMMENT '类型名称',
    `icon`        varchar(255)             DEFAULT NULL COMMENT '图标URL',
    `sort`        int unsigned    NOT NULL DEFAULT 0 COMMENT '展示顺序，越小越靠前',
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商户类型表';

DROP TABLE IF EXISTS `lk_shop`;
CREATE TABLE `lk_shop` (
    `id`          bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `name`        varchar(128)    NOT NULL COMMENT '商户名称',
    `type_id`     bigint unsigned NOT NULL COMMENT '类型ID（逻辑外键 lk_shop_type.id）',
    `images`      varchar(1024)   NOT NULL COMMENT '图片URL，多张逗号分隔',
    `area`        varchar(128)             DEFAULT NULL COMMENT '商圈',
    `address`     varchar(255)    NOT NULL COMMENT '详细地址',
    `longitude`   double          NOT NULL COMMENT '经度',
    `latitude`    double          NOT NULL COMMENT '纬度',
    `avg_price`   bigint unsigned          DEFAULT NULL COMMENT '人均价格，单位分',
    `sold`        int unsigned    NOT NULL DEFAULT 0 COMMENT '销量',
    `comments`    int unsigned    NOT NULL DEFAULT 0 COMMENT '评价数',
    `score`       int unsigned    NOT NULL DEFAULT 0 COMMENT '评分1~5分x10存储，如47=4.7',
    `open_hours`  varchar(32)              DEFAULT NULL COMMENT '营业时间，如10:00-22:00',
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_type_id` (`type_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商户表';

-- -----------------------------------------------------------
-- 优惠域
-- -----------------------------------------------------------

DROP TABLE IF EXISTS `lk_voucher`;
CREATE TABLE `lk_voucher` (
    `id`           bigint unsigned NOT NULL COMMENT '主键，券ID（雪花）',
    `shop_id`      bigint unsigned NOT NULL COMMENT '所属商户（逻辑外键 lk_shop.id）',
    `title`        varchar(255)    NOT NULL COMMENT '标题',
    `sub_title`    varchar(255)             DEFAULT NULL COMMENT '副标题',
    `rules`        varchar(1024)            DEFAULT NULL COMMENT '使用规则',
    `pay_value`    bigint unsigned NOT NULL COMMENT '支付金额，单位分，0=免费领取',
    `actual_value` bigint unsigned NOT NULL COMMENT '抵扣金额，单位分',
    `type`         tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1普通券/2秒杀券',
    `status`       tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1上架/2下架/3过期',
    `create_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_shop_status` (`shop_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='优惠券表';

DROP TABLE IF EXISTS `lk_seckill_voucher`;
CREATE TABLE `lk_seckill_voucher` (
    `id`          bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `voucher_id`  bigint unsigned NOT NULL COMMENT '关联券ID（逻辑外键 lk_voucher.id）',
    `init_stock`  int unsigned    NOT NULL COMMENT '初始库存（回源重建基准）',
    `stock`       int unsigned    NOT NULL COMMENT '当前库存',
    `min_level`   tinyint unsigned NOT NULL DEFAULT 0 COMMENT '参与所需最低会员等级，0不限',
    `begin_time`  datetime        NOT NULL COMMENT '开抢时间',
    `end_time`    datetime        NOT NULL COMMENT '结束时间',
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_voucher_id` (`voucher_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='秒杀券扩展表';

-- -----------------------------------------------------------
-- 交易域
-- -----------------------------------------------------------

DROP TABLE IF EXISTS `lk_voucher_order`;
CREATE TABLE `lk_voucher_order` (
    `id`                  bigint unsigned NOT NULL COMMENT '主键，即订单ID（雪花）',
    `user_id`             bigint unsigned NOT NULL COMMENT '下单用户',
    `voucher_id`          bigint unsigned NOT NULL COMMENT '购买的券',
    `status`              tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1已创建/2用户取消/3超时关闭',
    `reconciliation_status` tinyint unsigned NOT NULL DEFAULT 1 COMMENT '对账状态 1待处理/2异常/3不一致/4一致',
    `create_time`         datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `close_time`          datetime                 DEFAULT NULL COMMENT '关闭时间（取消/超时关闭时写入）',
    `update_time`         datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_voucher_id` (`voucher_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='券订单表';

DROP TABLE IF EXISTS `lk_voucher_reconcile_log`;
CREATE TABLE `lk_voucher_reconcile_log` (
    `id`                  bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `order_id`            bigint unsigned NOT NULL COMMENT '订单ID',
    `user_id`             bigint unsigned NOT NULL COMMENT '下单用户（分片冗余键）',
    `voucher_id`          bigint unsigned NOT NULL COMMENT '券ID',
    `trace_id`            bigint unsigned NOT NULL COMMENT '链路追踪ID（Lua生成）',
    `message_id`          varchar(64)              DEFAULT NULL COMMENT 'Kafka消息UUID（消费幂等关联）',
    `log_type`            tinyint         NOT NULL DEFAULT 1 COMMENT '1扣减/2恢复',
    `business_type`       tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1下单成功/2下单超时/3下单失败',
    `before_qty`          int                      DEFAULT NULL COMMENT '变动前库存',
    `change_qty`          int                      DEFAULT NULL COMMENT '变动数量',
    `after_qty`           int                      DEFAULT NULL COMMENT '变动后库存',
    `reconciliation_status` tinyint unsigned NOT NULL DEFAULT 1 COMMENT '1待处理/2异常/3不一致/4一致',
    `detail`              varchar(1024)            DEFAULT NULL COMMENT '差异说明',
    `create_time`         datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_message_id` (`message_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='对账流水表';

DROP TABLE IF EXISTS `lk_rollback_failure_log`;
CREATE TABLE `lk_rollback_failure_log` (
    `id`             bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `voucher_id`     bigint unsigned NOT NULL COMMENT '券ID',
    `user_id`        bigint unsigned NOT NULL COMMENT '用户ID',
    `order_id`       bigint unsigned          DEFAULT NULL COMMENT '订单ID（建单前失败可空）',
    `trace_id`       bigint unsigned          DEFAULT NULL COMMENT '链路追踪ID',
    `result_code`    int                      DEFAULT NULL COMMENT 'Lua返回码（对应BaseCode秒杀段）',
    `retry_attempts` int             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `source`         varchar(64)              DEFAULT NULL COMMENT '来源组件',
    `detail`         varchar(1024)            DEFAULT NULL COMMENT '失败详情',
    `create_time`    datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_voucher_user` (`voucher_id`, `user_id`),
    KEY `idx_trace_id` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='回滚失败日志表';

-- -----------------------------------------------------------
-- 社区域
-- -----------------------------------------------------------

DROP TABLE IF EXISTS `lk_post`;
CREATE TABLE `lk_post` (
    `id`           bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `user_id`      bigint unsigned NOT NULL COMMENT '作者',
    `shop_id`      bigint unsigned          DEFAULT NULL COMMENT '关联商户（探店帖可挂商户，可空）',
    `title`        varchar(255)    NOT NULL COMMENT '标题',
    `images`       varchar(2048)   NOT NULL DEFAULT '' COMMENT '图片URL，最多9张逗号分隔',
    `content`      varchar(2048)   NOT NULL COMMENT '正文',
    `liked`        int unsigned    NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，事实源lk_post_like）',
    `comments`     int unsigned    NOT NULL DEFAULT 0 COMMENT '评论数（冗余计数）',
    `viewed`       int unsigned    NOT NULL DEFAULT 0 COMMENT '浏览UV快照（HyperLogLog定时回写）',
    `audit_status` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0待审核/1通过/2驳回，Feed仅展示1',
    `create_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_shop_id` (`shop_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子表';

DROP TABLE IF EXISTS `lk_post_comment`;
CREATE TABLE `lk_post_comment` (
    `id`           bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `post_id`      bigint unsigned NOT NULL COMMENT '所属帖子',
    `user_id`      bigint unsigned NOT NULL COMMENT '评论者',
    `parent_id`    bigint unsigned NOT NULL DEFAULT 0 COMMENT '一级评论ID，0=自身为一级评论',
    `reply_id`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '被回复的评论ID，0=非回复',
    `content`      varchar(512)    NOT NULL COMMENT '评论内容',
    `liked`        int unsigned    NOT NULL DEFAULT 0 COMMENT '点赞数',
    `audit_status` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0待审核/1通过/2驳回',
    `create_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_post_time` (`post_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='评论表';

DROP TABLE IF EXISTS `lk_post_like`;
CREATE TABLE `lk_post_like` (
    `id`          bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `post_id`     bigint unsigned NOT NULL COMMENT '帖子ID',
    `user_id`     bigint unsigned NOT NULL COMMENT '点赞用户',
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user` (`post_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='点赞表';

DROP TABLE IF EXISTS `lk_follow`;
CREATE TABLE `lk_follow` (
    `id`             bigint unsigned NOT NULL COMMENT '主键（雪花）',
    `user_id`        bigint unsigned NOT NULL COMMENT '关注者（发起方）',
    `follow_user_id` bigint unsigned NOT NULL COMMENT '被关注者',
    `create_time`    datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_follow` (`user_id`, `follow_user_id`),
    KEY `idx_follow_user_id` (`follow_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='关注表';

-- -----------------------------------------------------------
-- 种子数据（虚构演示数据）
-- -----------------------------------------------------------

INSERT INTO `lk_shop_type` (`id`, `name`, `icon`, `sort`)
VALUES (1, '美食', '/icons/type-food.png', 1),
       (2, '咖啡饮品', '/icons/type-coffee.png', 2),
       (3, '火锅烧烤', '/icons/type-hotpot.png', 3),
       (4, '甜点烘焙', '/icons/type-bakery.png', 4),
       (5, '健身运动', '/icons/type-fitness.png', 5),
       (6, '丽人美发', '/icons/type-beauty.png', 6),
       (7, '亲子游乐', '/icons/type-kids.png', 7),
       (8, '休闲娱乐', '/icons/type-leisure.png', 8),
       (9, '超市便利', '/icons/type-market.png', 9),
       (10, '宠物服务', '/icons/type-pet.png', 10);

INSERT INTO `lk_shop` (`id`, `name`, `type_id`, `images`, `area`, `address`, `longitude`, `latitude`,
                       `avg_price`, `sold`, `comments`, `score`, `open_hours`)
VALUES (1, '山语茶餐厅（武林广场店）', 1, '/images/shop/1-1.jpg,/images/shop/1-2.jpg', '武林广场',
        '体育场路188号一层', 120.163200, 30.274500, 8800, 4215, 3035, 47, '10:00-22:00'),
       (2, '暮末咖啡馆', 2, '/images/shop/2-1.jpg,/images/shop/2-2.jpg', '西湖',
        '南山路121号', 120.152300, 30.248900, 4500, 2160, 1460, 46, '09:00-21:00'),
       (3, '老院铜锅涮肉', 3, '/images/shop/3-1.jpg,/images/shop/3-2.jpg', '拱宸桥',
        '台州路88号运河广场负一层', 120.141700, 30.318600, 12800, 12035, 8045, 48, '11:00-23:00'),
       (4, '甜时烘焙工作室', 4, '/images/shop/4-1.jpg,/images/shop/4-2.jpg', '钱江新城',
        '富春路299号万象城B1', 120.212600, 30.246800, 3900, 6519, 2291, 49, '10:00-21:30'),
       (5, '铁馆健身（滨江店）', 5, '/images/shop/5-1.jpg,/images/shop/5-2.jpg', '滨江',
        '江南大道588号恒鑫大厦3层', 120.198500, 30.208400, 19900, 987, 651, 45, '06:30-23:00'),
       (6, '云上美发', 6, '/images/shop/6-1.jpg,/images/shop/6-2.jpg', '黄龙',
        '曙光路120号黄龙万科中心2层', 120.135800, 30.269700, 15800, 1352, 928, 44, '10:00-21:00'),
       (7, '奇趣亲子乐园', 7, '/images/shop/7-1.jpg,/images/shop/7-2.jpg', '城西',
        '文一西路998号印象城4层', 120.081900, 30.288300, 9900, 3021, 1876, 47, '10:00-20:00'),
       (8, '星屿桌游馆', 8, '/images/shop/8-1.jpg,/images/shop/8-2.jpg', '武林广场',
        '中山北路607号现代城建大厦5层', 120.168900, 30.280100, 6800, 1743, 1102, 43, '13:00-02:00'),
       (9, '邻里生鲜超市', 9, '/images/shop/9-1.jpg,/images/shop/9-2.jpg', '钱江新城',
        '庆春东路118号', 120.205400, 30.252200, 5600, 8932, 4210, 42, '08:00-22:30'),
       (10, '毛毛宠物生活馆', 10, '/images/shop/10-1.jpg,/images/shop/10-2.jpg', '西湖',
        '古墩路599号', 120.109300, 30.276800, 7800, 1105, 803, 46, '09:30-21:00');
