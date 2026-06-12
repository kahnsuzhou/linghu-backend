package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 拼团拉新活动表
 */
@Data
@TableName("activity")
public class Activity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long productId;

    /**
     * 活动价格（通常0.1元）
     */
    private BigDecimal activityPrice;

    /**
     * 商品原价快照
     */
    private BigDecimal originalPrice;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /**
     * 每人最多发起邀请次数
     */
    private Integer maxInvitePerUser;

    /**
     * 总名额（0=不限）
     */
    private Integer totalQuota;

    /**
     * 已用名额
     */
    private Integer usedQuota;

    /**
     * 状态：1=启用, 0=关闭
     */
    private Integer status;

    private String description;

    /**
     * 平台每单补贴金额（供货价 - 活动价）
     */
    private BigDecimal subsidyPerOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
