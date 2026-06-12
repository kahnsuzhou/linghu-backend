package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 钱包流水表
 */
@Data
@TableName("wallet_transaction")
public class WalletTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 类型：RECHARGE=充值, PAY=支付, INCOME=收入, WITHDRAW=提现
     */
    private String type;

    /**
     * 金额：正=收入，负=支出
     */
    private BigDecimal amount;

    /**
     * 变动后余额
     */
    private BigDecimal balanceAfter;

    /**
     * 备注说明
     */
    private String remark;

    /**
     * 关联ID（订单ID等）
     */
    private Long refId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
