package com.linghu.dto;

import lombok.Data;

import java.util.List;

/**
 * 铺货计划DTO（品牌方）
 */
@Data
public class ReplenishmentPlanDTO {

    private List<Long> productIds;

    private Integer totalQuantity;

    private String city;

    private List<WarehouseAllocationDTO> warehouseAllocations;

    @Data
    public static class WarehouseAllocationDTO {
        private Long warehouseId;
        private Integer quantity;
        // 明细：每个productId分配的数量
        private List<ProductAllocationDTO> products;
    }

    @Data
    public static class ProductAllocationDTO {
        private Long productId;
        private Integer quantity;
    }
}
