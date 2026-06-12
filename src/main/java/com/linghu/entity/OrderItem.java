package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细表
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long productId;

    private Long brandId;

    /**
     * 发货仓库ID
     */
    private Long warehouseId;

    private Integer quantity;

    private BigDecimal price;

    /**
     * 关联的拣货作业单ID
     */
    private Long workOrderId;
}
