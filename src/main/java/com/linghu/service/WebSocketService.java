package com.linghu.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 消息推送服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 向仓主推送消息
     * 客户端订阅路径：/topic/warehouse/{warehouseId}
     *
     * @param warehouseId 仓库ID
     * @param type        消息类型：NEW_PICKING_ORDER / NEW_INBOUND_ORDER
     * @param workOrderId 作业单ID
     */
    public void notifyWarehouse(Long warehouseId, String type, Long workOrderId) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("workOrderId", workOrderId);
        message.put("timestamp", System.currentTimeMillis());

        String destination = "/topic/warehouse/" + warehouseId;
        log.info("推送消息到仓主: {}, 消息={}", destination, message);
        messagingTemplate.convertAndSend(destination, message);
    }

    /**
     * 向消费者推送订单状态更新
     * 客户端订阅路径：/topic/user/{userId}
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @param status  新状态
     */
    public void notifyUserOrderUpdate(Long userId, Long orderId, String status) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "ORDER_STATUS_UPDATE");
        message.put("orderId", orderId);
        message.put("status", status);
        message.put("timestamp", System.currentTimeMillis());

        String destination = "/topic/user/" + userId;
        log.info("推送订单状态更新到消费者: {}, 消息={}", destination, message);
        messagingTemplate.convertAndSend(destination, message);
    }

    /**
     * 向品牌方推送库存变更通知
     *
     * @param brandId   品牌方用户ID
     * @param productId 商品ID
     * @param message   消息内容
     */
    public void notifyBrandInventoryUpdate(Long brandId, Long productId, String messageText) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "INVENTORY_UPDATE");
        message.put("productId", productId);
        message.put("message", messageText);
        message.put("timestamp", System.currentTimeMillis());

        String destination = "/topic/brand/" + brandId;
        messagingTemplate.convertAndSend(destination, message);
    }
}
