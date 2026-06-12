package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外单批次表
 * status: 0=处理中, 1=已完成, 2=部分失败
 */
@Data
@TableName("external_batch")
public class ExternalBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批次号，格式：WD+年月日+6位序号 */
    private String batchNo;

    /** 所属品牌ID */
    private Long brandId;

    /** 上传文件名 */
    private String fileName;

    /** 总条数 */
    private Integer totalCount;

    /** 成功条数 */
    private Integer successCount;

    /** 失败条数 */
    private Integer failedCount;

    /**
     * 批次状态：0=处理中, 1=已完成, 2=部分失败
     */
    private Integer status;

    /** 操作人用户ID */
    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
