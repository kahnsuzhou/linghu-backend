package com.linghu.dto;

import lombok.Data;

import java.util.List;

/**
 * 完成拣货DTO
 */
@Data
public class CompletePickingDTO {

    private Long workOrderId;

    /**
     * 承运商（例如：顺丰、京东物流）
     */
    private String logisticsCarrier;

    /**
     * 物流单号（可选，如已知）
     */
    private String trackingNo;
}
