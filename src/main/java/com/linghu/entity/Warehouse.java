package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mini 仓表
 */
@Data
@TableName("warehouse")
public class Warehouse {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 仓主用户ID
     */
    private Long userId;

    private String name;

    private String address;

    /**
     * 纬度
     */
    private Double lat;

    /**
     * 经度
     */
    private Double lng;

    /**
     * 面积（平方米）
     */
    private BigDecimal areaSqm;

    /**
     * 仓库类型：MINI/STANDARD/LARGE
     */
    private String type;

    /**
     * 容积（立方厘米）
     */
    private Long capacityVolume;

    /**
     * 已用容积（立方厘米）
     */
    private Long usedVolume;

    /**
     * 服务费率（元/单）
     */
    private BigDecimal serviceFeeRate;

    /**
     * 支持的发货方式，JSON数组字符串
     * 可选值：express=快递配送, delivery=外卖配送, pickup=到仓自提
     * 示例：["express","delivery","pickup"]
     */
    private String supportedDeliveries;

    /**
     * 审核状态：PENDING=待审核, APPROVED=已通过, REJECTED=已拒绝
     */
    private String auditStatus;

    /**
     * 审核备注/拒绝原因
     */
    private String auditRemark;

    /**
     * 审核时间
     */
    private LocalDateTime auditedAt;

    /**
     * 审核人
     */
    private String auditedBy;

    /**
     * 状态：0=关闭, 1=开放（仅APPROVED的仓库可被设为1）
     */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
