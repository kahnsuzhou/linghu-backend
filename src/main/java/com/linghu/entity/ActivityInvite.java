package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动邀请记录表
 */
@Data
@TableName("activity_invite")
public class ActivityInvite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long inviterId;

    /** 被邀请人用户ID（注册后才有值） */
    private Long inviteeId;

    private String inviteePhone;

    /**
     * 邀请码（唯一，用于生成分享链接）
     */
    private String inviteCode;

    /**
     * 状态：PENDING=等待注册, REGISTERED=已注册,
     *       ORDERED=已下单, PICKED_UP=已核销
     */
    private String status;

    /** 邀请人0.1元订单ID */
    private Long inviterOrderId;

    /** 被邀请人0.1元订单ID */
    private Long inviteeOrderId;

    /** 自提码（6位数字） */
    private String pickUpCode;

    /** 自提二维码内容（inviteId:pickUpCode） */
    private String pickUpQr;

    /** 选择的自提仓库ID */
    private Long warehouseId;

    private LocalDateTime pickedUpAt;

    /** 核销仓主用户ID */
    private Long pickedUpBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
