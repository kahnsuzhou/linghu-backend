package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实物库存表
 */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long productId;

    private Long brandId;

    /**
     * 可用库存数量
     */
    private Integer quantity;

    /**
     * 锁定库存数量（下单后锁定，拣货完成后释放）
     */
    private Integer lockedQuantity;

    private LocalDateTime lastInboundAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
