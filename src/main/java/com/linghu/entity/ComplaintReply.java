package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("complaint_reply")
public class ComplaintReply {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long complaintId;
    private Long replierId;

    /** 0=消费者 1=仓主 2=品牌方 9=运营 */
    private Integer replierRole;
    private String content;
    private String images;
    private Integer isAuto;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
