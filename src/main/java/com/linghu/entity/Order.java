package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * C端订单表
 */
@Data
@TableName("`order`")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单号（唯一，格式：LH + yyyyMMddHHmmss + 随机4位）
     */
    private String orderSn;

    private Long userId;

    /**
     * 商品金额（不含运费）
     */
    private BigDecimal goodsAmount;

    /**
     * 运费（元）——自提0元；会员满30免运费
     */
    private BigDecimal shippingFee;

    private BigDecimal totalAmount;

    /**
     * 状态：PENDING_PAY=待支付, PENDING_DELIVERY=待发货,
     *       DELIVERING=待收货, FINISHED=已完成, CANCELLED=已取消
     */
    private String status;

    /**
     * 售后退款状态：REQUESTED=申请中, APPROVED=已批准, REJECTED=已拒绝, REFUNDED=已退款
     */
    private String refundStatus;

    /**
     * 退款原因（消费者填写）
     */
    private String refundReason;

    /**
     * 退款类型：RETURN=退货退款, REFUND_ONLY=仅退款
     */
    private String refundType;

    /**
     * 申请退款时间
     */
    private LocalDateTime refundRequestedAt;

    /**
     * 退款处理时间（运营操作时间）
     */
    private LocalDateTime refundHandledAt;

    /**
     * 运营处理备注（拒绝原因等）
     */
    private String refundRemark;

    /**
     * 配送方式：express=快递, same_day=当日达, instant=即时配
     */
    private String deliveryMode;

    /**
     * 收货人姓名
     */
    private String deliveryName;

    /**
     * 收货人手机
     */
    private String deliveryPhone;

    /**
     * 收货地址（完整地址字符串）
     */
    private String deliveryAddress;

    /**
     * 自提码（6位数字，仅自提订单，支付后生成）
     */
    private String pickUpCode;

    /**
     * 物流单号
     */
    private String logisticsNo;

    /**
     * 承运商
     */
    private String carrier;

    private LocalDateTime paidAt;

    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
