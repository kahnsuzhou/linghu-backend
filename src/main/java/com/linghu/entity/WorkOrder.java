package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作业单表（仓主操作单）
 * 类型：1=入库 2=拣货 3=调拨出 4=调拨入 5=盘点
 */
@Data
@TableName("work_order")
public class WorkOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 类型：1=入库, 2=拣货, 3=调拨出(仓间), 4=调拨入(仓间), 5=盘点,
     *       6=品牌方发起调拨出库, 7=品牌方发起退货出库
     */
    private Integer type;

    private Long warehouseId;

    /**
     * 调拨来源仓ID（type=3/4时使用）
     */
    private Long sourceWarehouseId;

    private Long brandId;

    /**
     * 关联C端订单号（type=2拣货时使用）
     */
    private String orderNo;

    /**
     * 入库单号（type=1入库时生成，格式：RK+年月日+6位序号）
     */
    private String inboundNo;

    /**
     * 出库单号（type=6调拨/type=7退货时生成，格式：CK+年月日+6位序号）
     */
    private String outboundNo;

    /**
     * 备注/原因（调拨目的地描述、退货原因等）
     */
    private String remark;

    /**
     * 配送方式（type=2拣货时使用）：express=快递, delivery=外卖, pickup=自提
     */
    private String deliveryMode;

    /**
     * 状态：PENDING=待处理, PROCESSING=处理中, COMPLETED=已完成, CANCELLED=已取消
     */
    private String status;

    /**
     * 作业明细（JSON格式）
     * 例如：[{"productId":1,"productName":"商品A","barcode":"123456","planQuantity":10,"actualQuantity":0}]
     */
    private String items;

    /**
     * 操作人（仓主）用户ID
     */
    private Long operatorId;

    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
