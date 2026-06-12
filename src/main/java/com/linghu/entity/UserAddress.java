package com.linghu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_address")
public class UserAddress {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 联系人姓名 */
    private String name;

    /** 联系电话 */
    private String phone;

    private String province;
    private String city;
    private String district;

    /** 详细地址 */
    private String detail;

    /** GPS 纬度 */
    private BigDecimal latitude;

    /** GPS 经度 */
    private BigDecimal longitude;

    /** 是否为默认地址：0否 1是 */
    private Integer isDefault;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
