package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付订单（充值 / 提现记录）
 * 注意：避免与业务订单表 Order 混淆，类名用 PaymentOrder
 */
@Data
@TableName("payment_order")
public class PaymentOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 业务单号（系统生成，唯一） */
    private String orderNo;

    /** 支付渠道：ALIPAY | WECHAT */
    private String channel;

    /** 类型：RECHARGE=充值, WITHDRAW=提现 */
    private String type;

    /** 金额（正数） */
    private BigDecimal amount;

    /** 状态：PENDING | SUCCESS | FAILED | CLOSED */
    private String status;

    /** 第三方流水号（支付宝交易号 / 微信支付 transaction_id） */
    private String channelOrderNo;

    /** H5 支付跳转链接（充值时由第三方返回） */
    private String payUrl;

    /** 原始回调报文 */
    private String callbackRaw;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
