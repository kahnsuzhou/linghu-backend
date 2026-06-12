package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户绑定的支付账号（支付宝 / 微信）
 */
@Data
@TableName("user_payment_account")
public class UserPaymentAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 渠道：ALIPAY | WECHAT */
    private String channel;

    /** 支付宝登录账号（手机号/邮箱）或微信 OpenID */
    private String accountNo;

    /** 真实姓名（提现时用于账户验证） */
    private String realName;

    /** 是否为默认账号：0=否 1=是 */
    private Integer isDefault;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
