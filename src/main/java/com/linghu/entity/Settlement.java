package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分账记录表
 */
@Data
@TableName("settlement")
public class Settlement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /**
     * 分账对象类型：1=品牌方, 2=仓主, 3=骑手, 4=平台
     */
    private Integer targetType;

    private Long targetId;

    private BigDecimal amount;

    /**
     * 状态：PENDING=待结算, SETTLED=已结算
     */
    private String status;

    private LocalDateTime settledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
