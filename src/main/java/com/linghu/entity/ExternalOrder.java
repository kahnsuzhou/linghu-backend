package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外单表
 * status: 0=待处理, 1=已取消, 2=拣货中, 3=已发货, 4=异常
 */
@Data
@TableName("external_order")
public class ExternalOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属批次ID */
    private Long batchId;

    /** 品牌ID */
    private Long brandId;

    /** 分配的仓库ID（分仓失败时为null） */
    private Long warehouseId;

    /** 关联工单ID */
    private Long workOrderId;

    /** 外单号（品牌方自有单号） */
    private String externalOrderNo;

    /** 来源渠道（如：淘宝、京东、抖音） */
    private String channel;

    /** 商品名称 */
    private String productName;

    /** SKU编码 */
    private String skuCode;

    /** 数量 */
    private Integer quantity;

    /** 收件人姓名 */
    private String receiverName;

    /** 收件人手机号 */
    private String receiverPhone;

    /** 收件人地址 */
    private String receiverAddress;

    /**
     * 状态：0=待处理, 1=已取消, 2=拣货中, 3=已发货, 4=异常
     */
    private Integer status;

    /** 物流公司 */
    private String logisticsCompany;

    /** 物流单号 */
    private String logisticsNo;

    /** 发货时间 */
    private LocalDateTime shipTime;

    /** 异常原因（status=4时填写） */
    private String exceptionReason;

    /** 失败原因（分仓失败时填写，如"库存不足"） */
    private String failReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
