package com.linghu.dto;

import lombok.Data;

import java.util.List;

/**
 * 完成入库DTO
 */
@Data
public class CompleteInboundDTO {

    private Long workOrderId;

    private List<InboundItemDTO> items;

    @Data
    public static class InboundItemDTO {
        private Long productId;
        private String barcode;
        private Integer actualQuantity;
    }
}
