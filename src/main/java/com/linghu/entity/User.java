package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表 - 统一账户（消费者/仓主/品牌方共用）
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String phone;

    /**
     * 角色：0=消费者, 1=仓主, 2=品牌方
     */
    private Integer role;

    /**
     * VIP等级：0=普通, 1=月会员, 2=季会员, 3=年会员
     * 消费者会员享满30免运费；仓主VIP(≥1)可开更多仓库
     */
    private Integer vipLevel;

    /**
     * 消费者VIP到期时间（仓主无需此字段）
     */
    private LocalDateTime vipExpireTime;

    private String avatar;

    /**
     * 状态：0=禁用, 1=正常
     */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
