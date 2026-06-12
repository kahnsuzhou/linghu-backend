package com.linghu.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 创建订单DTO
 */
@Data
public class CreateOrderDTO {

    @NotEmpty(message = "订单商品不能为空")
    private List<OrderItemDTO> items;

    private String deliveryMode;

    private Long addressId;

    @Data
    public static class OrderItemDTO {
        @NotNull
        private Long productId;
        @NotNull
        private Long warehouseId;
        @NotNull
        private Integer quantity;
    }
}
