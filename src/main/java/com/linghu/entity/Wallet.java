package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户钱包表
 */
@Data
@TableName("wallet")
public class Wallet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 可用余额（元）
     */
    private BigDecimal balance;

    /**
     * 冻结金额（元）
     */
    private BigDecimal frozen;

    /**
     * 支付密码（BCrypt加密，null表示未设置）
     */
    private String payPassword;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
