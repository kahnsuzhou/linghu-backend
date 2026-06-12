package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("complaint")
public class Complaint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String phone;
    private String content;
    private String images;
    private String voiceUrl;

    /** ORDER / PRODUCT / WAREHOUSE / NONE */
    private String relatedType;
    private Long relatedId;

    /** 冗余订单号，方便展示 */
    private String orderSn;

    /** 关联仓库ID（提交时自动从订单/商品解析） */
    private Long warehouseId;

    /** 关联品牌方ID（提交时自动从商品解析） */
    private Long brandId;

    private Integer isUrgent;
    private Integer aiUrgent;
    private Integer isAnonymous;
    private String aiCategory;
    private Double aiConfidence;

    /** PENDING / PROCESSING / REPLIED / RESOLVED */
    private String status;

    private Long assignedTo;
    private LocalDateTime replyDeadline;
    private Integer overdue;

    /** 1=满意 2=一般 3=不满意 */
    private Integer satisfaction;
    private String satComment;
    private LocalDateTime satTime;
    private Integer compensated;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
