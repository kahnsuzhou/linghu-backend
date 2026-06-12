-- ============================================
-- 灵狐·优库近选 数据库初始化脚本
-- MySQL 8.0 | utf8mb4 | InnoDB
-- ============================================

CREATE DATABASE IF NOT EXISTS linghu_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE linghu_db;

-- ============================================
-- 1. 用户表（统一账户）
-- ============================================
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `role`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '角色：0=消费者, 1=仓主, 2=品牌方',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `status`      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态：0=禁用, 1=正常',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常, 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================
-- 2. 品牌方详细信息表
-- ============================================
DROP TABLE IF EXISTS `brand`;
CREATE TABLE `brand` (
    `id`               BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '品牌方ID',
    `user_id`          BIGINT(20)   NOT NULL COMMENT '关联用户ID',
    `company_name`     VARCHAR(100) NOT NULL COMMENT '公司名称',
    `contact_person`   VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    `legal_person`     VARCHAR(50)  DEFAULT NULL COMMENT '法人',
    `business_license` VARCHAR(100) DEFAULT NULL COMMENT '营业执照号',
    `status`           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '状态：0=待审核, 1=正常, 2=禁用',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='品牌方信息表';

-- ============================================
-- 3. Mini 仓表
-- ============================================
DROP TABLE IF EXISTS `warehouse`;
CREATE TABLE `warehouse` (
    `id`               BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
    `user_id`          BIGINT(20)     NOT NULL COMMENT '仓主用户ID',
    `name`             VARCHAR(100)   NOT NULL COMMENT '仓库名称',
    `address`          VARCHAR(255)   NOT NULL DEFAULT '' COMMENT '详细地址',
    `lat`              DECIMAL(10, 7) DEFAULT NULL COMMENT '纬度',
    `lng`              DECIMAL(10, 7) DEFAULT NULL COMMENT '经度',
    `capacity_volume`  BIGINT(20)     DEFAULT 1000000 COMMENT '总容积（立方厘米）',
    `used_volume`      BIGINT(20)     DEFAULT 0 COMMENT '已用容积（立方厘米）',
    `service_fee_rate` DECIMAL(10, 2) NOT NULL DEFAULT 2.00 COMMENT '服务费率（元/单）',
    `status`           TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '状态：0=关闭, 1=开放',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`          TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_location` (`lat`, `lng`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mini仓表';

-- ============================================
-- 4. 商品表
-- ============================================
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
    `id`          BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `brand_id`    BIGINT(20)     NOT NULL COMMENT '品牌方ID',
    `sku_code`    VARCHAR(50)    NOT NULL COMMENT 'SKU编码',
    `name`        VARCHAR(100)   NOT NULL COMMENT '商品名称',
    `barcode`     VARCHAR(50)    DEFAULT NULL COMMENT '商品条码（EAN13/UPC）',
    `retail_price` DECIMAL(10, 2) NOT NULL COMMENT '零售价（元）',
    `weight_g`    INT(11)        DEFAULT NULL COMMENT '重量（克）',
    `volume_cm3`  INT(11)        DEFAULT NULL COMMENT '体积（立方厘米）',
    `images`      TEXT           DEFAULT NULL COMMENT '图片URL列表（JSON数组）',
    `status`      TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '状态：0=下架, 1=上架',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_brand_id` (`brand_id`),
    KEY `idx_barcode` (`barcode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

-- ============================================
-- 5. 实物库存表
-- ============================================
DROP TABLE IF EXISTS `inventory`;
CREATE TABLE `inventory` (
    `id`               BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '库存ID',
    `warehouse_id`     BIGINT(20) NOT NULL COMMENT '仓库ID',
    `product_id`       BIGINT(20) NOT NULL COMMENT '商品ID',
    `brand_id`         BIGINT(20) NOT NULL COMMENT '品牌方ID',
    `quantity`         INT(11)    NOT NULL DEFAULT 0 COMMENT '可用库存数量',
    `locked_quantity`  INT(11)    NOT NULL DEFAULT 0 COMMENT '锁定库存数量',
    `last_inbound_at`  DATETIME   DEFAULT NULL COMMENT '最后入库时间',
    `create_time`      DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_warehouse_product` (`warehouse_id`, `product_id`),
    KEY `idx_brand_id` (`brand_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实物库存表';

-- ============================================
-- 6. 作业单表
-- ============================================
DROP TABLE IF EXISTS `work_order`;
CREATE TABLE `work_order` (
    `id`                  BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '作业单ID',
    `type`                TINYINT(1)   NOT NULL COMMENT '类型：1=入库, 2=拣货, 3=调拨出, 4=调拨入, 5=盘点',
    `warehouse_id`        BIGINT(20)   NOT NULL COMMENT '目标仓库ID',
    `source_warehouse_id` BIGINT(20)   DEFAULT NULL COMMENT '来源仓库ID（调拨时使用）',
    `brand_id`            BIGINT(20)   DEFAULT NULL COMMENT '品牌方ID',
    `order_no`            VARCHAR(50)  DEFAULT NULL COMMENT '关联C端订单号（拣货时）',
    `status`              VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/PROCESSING/COMPLETED/CANCELLED',
    `items`               JSON         DEFAULT NULL COMMENT '作业明细（JSON）',
    `operator_id`         BIGINT(20)   DEFAULT NULL COMMENT '操作人（仓主）ID',
    `completed_at`        DATETIME     DEFAULT NULL COMMENT '完成时间',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_warehouse_id` (`warehouse_id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作业单表';

-- ============================================
-- 7. C端订单表
-- ============================================
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order` (
    `id`            BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_sn`      VARCHAR(50)    NOT NULL COMMENT '订单号',
    `user_id`       BIGINT(20)     NOT NULL COMMENT '消费者用户ID',
    `total_amount`  DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
    `status`        VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAY' COMMENT '状态：PENDING_PAY/PENDING_DELIVERY/DELIVERING/FINISHED/CANCELLED',
    `delivery_mode` VARCHAR(20)    DEFAULT 'express' COMMENT '配送方式：express/same_day/instant',
    `logistics_no`  VARCHAR(50)    DEFAULT NULL COMMENT '物流单号',
    `carrier`       VARCHAR(50)    DEFAULT NULL COMMENT '承运商',
    `paid_at`       DATETIME       DEFAULT NULL COMMENT '支付时间',
    `finished_at`   DATETIME       DEFAULT NULL COMMENT '完成时间',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`       TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='C端订单表';

-- ============================================
-- 8. 订单明细表
-- ============================================
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
    `id`           BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `order_id`     BIGINT(20)     NOT NULL COMMENT '订单ID',
    `product_id`   BIGINT(20)     NOT NULL COMMENT '商品ID',
    `brand_id`     BIGINT(20)     NOT NULL COMMENT '品牌方ID',
    `warehouse_id` BIGINT(20)     NOT NULL COMMENT '发货仓库ID',
    `quantity`     INT(11)        NOT NULL COMMENT '数量',
    `price`        DECIMAL(10, 2) NOT NULL COMMENT '单价（下单时快照）',
    `work_order_id` BIGINT(20)    DEFAULT NULL COMMENT '关联拣货作业单ID',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

-- ============================================
-- 9. 分账记录表
-- ============================================
DROP TABLE IF EXISTS `settlement`;
CREATE TABLE `settlement` (
    `id`          BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT '分账ID',
    `order_id`    BIGINT(20)     NOT NULL COMMENT '订单ID',
    `target_type` TINYINT(1)     NOT NULL COMMENT '分账对象：1=品牌方, 2=仓主, 3=骑手, 4=平台',
    `target_id`   BIGINT(20)     NOT NULL COMMENT '分账对象ID',
    `amount`      DECIMAL(10, 2) NOT NULL COMMENT '分账金额',
    `status`      VARCHAR(20)    NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING=待结算, SETTLED=已结算',
    `settled_at`  DATETIME       DEFAULT NULL COMMENT '结算时间',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分账记录表';


-- ============================================
-- 测试数据
-- ============================================

-- 用户（密码均为 123456 的 BCrypt 加密结果）
INSERT INTO `user` (`id`, `username`, `password`, `phone`, `role`, `avatar`, `status`) VALUES
(1, 'consumer', '$2a$10$EqKzIh4FzC5P2gZxKQXCB.VZjq8OWkiYcFJVSLZX2qfRUVQEKsL2y', '13800000001', 0, 'https://api.dicebear.com/7.x/avataaars/svg?seed=consumer', 1),
(2, 'warehouse', '$2a$10$EqKzIh4FzC5P2gZxKQXCB.VZjq8OWkiYcFJVSLZX2qfRUVQEKsL2y', '13800000002', 1, 'https://api.dicebear.com/7.x/avataaars/svg?seed=warehouse', 1),
(3, 'brand', '$2a$10$EqKzIh4FzC5P2gZxKQXCB.VZjq8OWkiYcFJVSLZX2qfRUVQEKsL2y', '13800000003', 2, 'https://api.dicebear.com/7.x/avataaars/svg?seed=brand', 1);

-- 品牌方信息
INSERT INTO `brand` (`id`, `user_id`, `company_name`, `contact_person`, `legal_person`, `business_license`, `status`) VALUES
(1, 3, '灵狐优品科技有限公司', '张品牌', '张法人', '91110000XXXXXXXXX', 1);

-- Mini 仓信息
INSERT INTO `warehouse` (`id`, `user_id`, `name`, `address`, `lat`, `lng`, `capacity_volume`, `used_volume`, `service_fee_rate`, `status`) VALUES
(1, 2, '朝阳区望京Mini仓', '北京市朝阳区望京街道5号', 40.0010, 116.4810, 5000000, 1200000, 3.50, 1),
(2, 2, '海淀区中关村Mini仓', '北京市海淀区中关村南大街27号', 39.9780, 116.3120, 3000000, 800000, 2.50, 1);

-- 商品
INSERT INTO `product` (`id`, `brand_id`, `sku_code`, `name`, `barcode`, `retail_price`, `weight_g`, `volume_cm3`, `images`, `status`) VALUES
(1, 1, 'LH-SKU-001', '灵狐有机牛奶250ml', '6901234567890', 5.90, 260, 300, '["https://picsum.photos/seed/milk/400/400"]', 1),
(2, 1, 'LH-SKU-002', '灵狐坚果混合装200g', '6901234567891', 29.90, 200, 250, '["https://picsum.photos/seed/nuts/400/400"]', 1),
(3, 1, 'LH-SKU-003', '灵狐矿泉水500ml', '6901234567892', 2.50, 500, 550, '["https://picsum.photos/seed/water/400/400"]', 1),
(4, 1, 'LH-SKU-004', '灵狐绿茶饮料330ml', '6901234567893', 4.50, 350, 400, '["https://picsum.photos/seed/tea/400/400"]', 1),
(5, 1, 'LH-SKU-005', '灵狐全麦饼干100g', '6901234567894', 8.80, 100, 200, '["https://picsum.photos/seed/cookies/400/400"]', 1);

-- 库存
INSERT INTO `inventory` (`warehouse_id`, `product_id`, `brand_id`, `quantity`, `locked_quantity`, `last_inbound_at`) VALUES
(1, 1, 1, 100, 0, NOW()),
(1, 2, 1, 50,  0, NOW()),
(1, 3, 1, 200, 0, NOW()),
(1, 4, 1, 80,  0, NOW()),
(1, 5, 1, 60,  0, NOW()),
(2, 1, 1, 80,  0, NOW()),
(2, 3, 1, 150, 0, NOW()),
(2, 5, 1, 40,  0, NOW());
