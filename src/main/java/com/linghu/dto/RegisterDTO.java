package com.linghu.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 注册请求DTO
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String phone;

    /**
     * 角色：0=消费者, 1=仓主, 2=品牌方
     */
    @NotNull(message = "角色不能为空")
    private Integer role;

    // 仓主/品牌方注册时的扩展信息
    private String companyName;
    private String contactPerson;
    private String warehouseName;
    private String warehouseAddress;
}
