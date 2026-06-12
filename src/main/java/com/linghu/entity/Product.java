package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品表
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long brandId;

    /**
     * 商品来源类型：brand=品牌商品, self=自产商品
     */
    private String sourceType;

    /**
     * SKU 编码
     */
    private String skuCode;

    private String name;

    /**
     * 商品条码
     */
    private String barcode;

    /**
     * 零售价（元）
     */
    private BigDecimal retailPrice;

    /**
     * 重量（克）
     */
    private Integer weightG;

    /**
     * 体积（立方厘米）
     */
    private Integer volumeCm3;

    /**
     * 图片URL（JSON数组格式）
     */
    private String images;

    /**
     * 状态：0=下架, 1=上架
     */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
