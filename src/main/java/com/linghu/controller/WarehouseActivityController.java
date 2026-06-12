package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/warehouse/pickup")
@RequiredArgsConstructor
public class WarehouseActivityController {

    private final ActivityInviteMapper activityInviteMapper;
    private final ActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final WarehouseMapper warehouseMapper;
    private final InventoryMapper inventoryMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /**
     * 待核销自提订单列表（按仓库）
     * GET /api/warehouse/pickup/list
     * 返回：活动订单 + 普通自提订单（delivery_mode=pickup）合并列表
     */
    @GetMapping("/list")
    @RequireRole(1)
    public R<List<Map<String, Object>>> getPickupList(
            @RequestParam(required = false) String keyword) {
        Long userId = SecurityUtil.getCurrentUserId();

        // 查此仓主负责的仓库
        List<Warehouse> myWarehouses = warehouseMapper.selectList(
            new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0));
        if (myWarehouses.isEmpty()) return R.ok(Collections.emptyList());
        List<Long> warehouseIds = new ArrayList<>();
        for (Warehouse w : myWarehouses) warehouseIds.add(w.getId());

        List<Map<String, Object>> result = new ArrayList<>();

        // ── 1. 活动订单自提码 ──────────────────────────────────────────
        LambdaQueryWrapper<ActivityInvite> wrapper = new LambdaQueryWrapper<ActivityInvite>()
            .in(ActivityInvite::getWarehouseId, warehouseIds)
            .isNotNull(ActivityInvite::getPickUpCode)
            .in(ActivityInvite::getStatus, Arrays.asList("PENDING", "REGISTERED", "ORDERED", "PICKED_UP"))
            .orderByAsc(ActivityInvite::getStatus)
            .orderByDesc(ActivityInvite::getCreateTime);

        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(ActivityInvite::getPickUpCode, keyword)
                .or().like(ActivityInvite::getInviteePhone, keyword));
        }

        List<ActivityInvite> invites = activityInviteMapper.selectList(wrapper);
        for (ActivityInvite i : invites) {
            Activity a = activityMapper.selectById(i.getActivityId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceType", "activity");   // 标记来源
            item.put("inviteId", i.getId());
            item.put("pickUpCode", i.getPickUpCode());
            item.put("pickUpQr", i.getPickUpQr());
            item.put("status", i.getStatus());
            String statusText;
            switch (i.getStatus()) {
                case "ORDERED":    statusText = "待核销"; break;
                case "PICKED_UP":  statusText = "已核销"; break;
                case "PENDING":    statusText = "待核销(好友待注册)"; break;
                case "REGISTERED": statusText = "待核销(好友已注册)"; break;
                default:           statusText = i.getStatus();
            }
            item.put("statusText", statusText);
            item.put("isPending", !"PICKED_UP".equals(i.getStatus()));
            item.put("createTime", i.getCreateTime());
            item.put("pickedUpAt", i.getPickedUpAt());
            if (a != null) {
                item.put("activityName", a.getName());
                item.put("activityPrice", a.getActivityPrice());
                Product p = productMapper.selectById(a.getProductId());
                if (p != null) {
                    item.put("productName", p.getName());
                    item.put("productImage", p.getImages());
                }
            }
            Long consumerId = i.getInviteeId() != null ? i.getInviteeId() : i.getInviterId();
            User consumer = userMapper.selectById(consumerId);
            item.put("consumerName", consumer != null ? consumer.getUsername() : "");
            item.put("consumerPhone", consumer != null ? consumer.getPhone() : "");
            result.add(item);
        }

        // ── 2. 普通自提订单（delivery_mode=pickup）─────────────────────
        // 通过 order_item 找到属于本仓主仓库的 pickup 订单
        List<OrderItem> myItems = orderItemMapper.selectList(
            new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getWarehouseId, warehouseIds));

        if (!myItems.isEmpty()) {
            // 收集所有 orderId
            Set<Long> orderIds = new HashSet<>();
            Map<Long, Long> orderIdToWarehouse = new HashMap<>();
            for (OrderItem oi : myItems) {
                orderIds.add(oi.getOrderId());
                orderIdToWarehouse.put(oi.getOrderId(), oi.getWarehouseId());
            }

            // 查这些 orderId 里 delivery_mode=pickup 且未取消的
            LambdaQueryWrapper<Order> orderWrapper = new LambdaQueryWrapper<Order>()
                .in(Order::getId, orderIds)
                .eq(Order::getDeliveryMode, "pickup")
                .ne(Order::getStatus, "CANCELLED")
                .eq(Order::getDeleted, 0)
                .orderByDesc(Order::getCreateTime);

            // keyword 过滤
            if (keyword != null && !keyword.isBlank()) {
                orderWrapper.and(w -> w
                    .like(Order::getPickUpCode, keyword)
                    .or().like(Order::getOrderSn, keyword));
            }

            List<Order> pickupOrders = orderMapper.selectList(orderWrapper);
            for (Order order : pickupOrders) {
                Long whId = orderIdToWarehouse.get(order.getId());
                Warehouse wh = warehouseMapper.selectById(whId);

                // 组装商品名
                List<OrderItem> orderItems = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId())
                        .eq(OrderItem::getWarehouseId, whId));
                StringBuilder productNames = new StringBuilder();
                String firstImage = "";
                for (OrderItem oi : orderItems) {
                    Product p = productMapper.selectById(oi.getProductId());
                    if (productNames.length() > 0) productNames.append("、");
                    productNames.append(p != null ? p.getName() : "商品").append("×").append(oi.getQuantity());
                    if (firstImage.isEmpty() && p != null && p.getImages() != null) {
                        firstImage = p.getImages();
                    }
                }

                // 统一状态映射
                String orderStatus = order.getStatus();
                String pickupStatus;   // 转换为前端已知的状态值
                String pickupStatusText;
                boolean isPending;
                if ("FINISHED".equals(orderStatus)) {
                    pickupStatus = "PICKED_UP";
                    pickupStatusText = "已核销";
                    isPending = false;
                } else {
                    // PENDING_DELIVERY / DELIVERING → 待核销
                    pickupStatus = "ORDERED";
                    pickupStatusText = "待核销";
                    isPending = true;
                }

                User consumer = userMapper.selectById(order.getUserId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceType", "order");           // 标记来源为普通订单
                item.put("orderId", order.getId());
                item.put("orderSn", order.getOrderSn());
                item.put("pickUpCode", order.getPickUpCode() != null ? order.getPickUpCode() : "");
                item.put("pickUpQr", null);
                item.put("status", pickupStatus);
                item.put("statusText", pickupStatusText);
                item.put("isPending", isPending);
                item.put("productName", productNames.toString());
                item.put("productImage", firstImage);
                item.put("totalAmount", order.getTotalAmount());
                item.put("warehouseName", wh != null ? wh.getName() : "");
                item.put("createTime", order.getCreateTime());
                item.put("pickedUpAt", order.getFinishedAt());
                item.put("consumerName", consumer != null ? consumer.getUsername() : "");
                item.put("consumerPhone", consumer != null ? consumer.getPhone() : "");
                result.add(item);
            }
        }

        // 待核销的排最前面，已核销的排后面，同状态内按时间倒序
        result.sort((a, b) -> {
            boolean aPending = Boolean.TRUE.equals(a.get("isPending"));
            boolean bPending = Boolean.TRUE.equals(b.get("isPending"));
            if (aPending != bPending) return aPending ? -1 : 1;
            return 0;
        });

        return R.ok(result);
    }

    /**
     * 核销自提码
     * POST /api/warehouse/pickup/verify
     * Body: { "pickUpCode": "123456" }           ← 通用，自动识别活动/普通订单
     * 兼容: { "pickUpQr": "inviteId:pickUpCode" } 或 { "pickUpCode": "...", "inviteId": 1 }
     *       { "pickUpCode": "...", "orderId": 49 } ← 普通订单直接核销
     */
    @PostMapping("/verify")
    @RequireRole(1)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> verifyPickup(@RequestBody Map<String, Object> body) {
        Long operatorUserId = SecurityUtil.getCurrentUserId();

        String pickUpQr   = (String) body.get("pickUpQr");
        String pickUpCode = (String) body.get("pickUpCode");
        Long inviteId = body.get("inviteId") != null
            ? ((Number) body.get("inviteId")).longValue() : null;
        Long orderId = body.get("orderId") != null
            ? ((Number) body.get("orderId")).longValue() : null;

        // ── 解析 pickUpQr 兼容旧格式 ──
        if (pickUpQr != null && !pickUpQr.isBlank()) {
            String[] parts = pickUpQr.split(":");
            if (parts.length != 2) throw new BusinessException("无效的自提码格式");
            inviteId   = Long.parseLong(parts[0]);
            pickUpCode = parts[1];
        }
        if (pickUpCode == null || pickUpCode.isBlank()) throw new BusinessException("请输入自提码");

        // ── 查当前仓主负责的仓库 ──
        List<Warehouse> myWarehouses = warehouseMapper.selectList(
            new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, operatorUserId)
                .eq(Warehouse::getDeleted, 0));
        if (myWarehouses.isEmpty()) throw new BusinessException("您尚未创建仓库");
        List<Long> myWarehouseIds = new ArrayList<>();
        for (Warehouse w : myWarehouses) myWarehouseIds.add(w.getId());

        // ── 优先尝试普通订单核销 ──────────────────────────────────────
        // 条件：直接指定 orderId，或按 pickUpCode 在本仓库的 pickup 订单中匹配
        Order matchedOrder = null;
        if (orderId != null) {
            // 直接指定 orderId
            Order o = orderMapper.selectById(orderId);
            if (o != null && "pickup".equals(o.getDeliveryMode()) && !o.getDeleted().equals(1)) {
                // 验证自提码匹配
                if (!pickUpCode.equals(o.getPickUpCode())) throw new BusinessException("自提码不正确");
                // 验证属于本仓主
                List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
                boolean belongsToMe = items.stream().anyMatch(i -> myWarehouseIds.contains(i.getWarehouseId()));
                if (!belongsToMe) throw new BusinessException("该订单不属于您的仓库");
                matchedOrder = o;
            }
        }

        if (matchedOrder == null && inviteId == null) {
            // 按 pickUpCode 在本仓库的 pickup 订单中匹配
            List<OrderItem> myItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().in(OrderItem::getWarehouseId, myWarehouseIds));
            if (!myItems.isEmpty()) {
                Set<Long> orderIds = new HashSet<>();
                for (OrderItem oi : myItems) orderIds.add(oi.getOrderId());
                Order o = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .in(Order::getId, orderIds)
                    .eq(Order::getDeliveryMode, "pickup")
                    .eq(Order::getPickUpCode, pickUpCode)
                    .eq(Order::getDeleted, 0)
                    .last("LIMIT 1"));
                if (o != null) matchedOrder = o;
            }
        }

        if (matchedOrder != null) {
            // ── 普通订单核销逻辑 ──
            if ("FINISHED".equals(matchedOrder.getStatus())) {
                throw new BusinessException("该自提码已核销，请勿重复核销");
            }
            if ("CANCELLED".equals(matchedOrder.getStatus())) {
                throw new BusinessException("订单已取消，无法核销");
            }
            // 核销：状态改为 FINISHED
            matchedOrder.setStatus("FINISHED");
            matchedOrder.setFinishedAt(LocalDateTime.now());
            orderMapper.updateById(matchedOrder);
            log.info("普通自提订单核销成功: orderId={}, pickUpCode={}, operatorId={}",
                matchedOrder.getId(), pickUpCode, operatorUserId);

            // 获取商品信息
            List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, matchedOrder.getId()));
            StringBuilder productNames = new StringBuilder();
            for (OrderItem oi : items) {
                Product p = productMapper.selectById(oi.getProductId());
                if (productNames.length() > 0) productNames.append("、");
                productNames.append(p != null ? p.getName() : "商品").append("×").append(oi.getQuantity());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceType", "order");
            result.put("orderId", matchedOrder.getId());
            result.put("orderSn", matchedOrder.getOrderSn());
            result.put("status", "PICKED_UP");
            result.put("pickedUpAt", matchedOrder.getFinishedAt());
            result.put("productName", productNames.toString());
            result.put("totalAmount", matchedOrder.getTotalAmount());
            return R.ok("核销成功！", result);
        }

        // ── 活动订单核销逻辑（原有逻辑保持不变）────────────────────────
        ActivityInvite invite;
        if (inviteId != null) {
            invite = activityInviteMapper.selectById(inviteId);
            if (invite == null) throw new BusinessException("自提记录不存在");
            if (!invite.getPickUpCode().equals(pickUpCode)) throw new BusinessException("自提码不正确");
            if (!myWarehouseIds.contains(invite.getWarehouseId())) throw new BusinessException("该自提订单不属于您的仓库");
        } else {
            invite = activityInviteMapper.selectOne(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getPickUpCode, pickUpCode)
                    .in(ActivityInvite::getStatus, Arrays.asList("PENDING", "REGISTERED", "ORDERED"))
                    .in(ActivityInvite::getWarehouseId, myWarehouseIds)
                    .last("LIMIT 1"));
            if (invite == null) {
                ActivityInvite pickedUp = activityInviteMapper.selectOne(
                    new LambdaQueryWrapper<ActivityInvite>()
                        .eq(ActivityInvite::getPickUpCode, pickUpCode)
                        .eq(ActivityInvite::getStatus, "PICKED_UP")
                        .in(ActivityInvite::getWarehouseId, myWarehouseIds)
                        .last("LIMIT 1"));
                if (pickedUp != null) throw new BusinessException("该自提码已核销，请勿重复核销");
                throw new BusinessException("自提码无效或不属于您的仓库");
            }
        }

        if ("PICKED_UP".equals(invite.getStatus())) throw new BusinessException("该自提码已核销，请勿重复核销");
        List<String> canVerifyStatuses = Arrays.asList("PENDING", "REGISTERED", "ORDERED");
        if (!canVerifyStatuses.contains(invite.getStatus())) throw new BusinessException("当前状态不可核销（" + invite.getStatus() + "）");

        invite.setStatus("PICKED_UP");
        invite.setPickedUpAt(LocalDateTime.now());
        invite.setPickedUpBy(operatorUserId);
        activityInviteMapper.updateById(invite);

        Activity a = activityMapper.selectById(invite.getActivityId());
        if (a != null) {
            inventoryMapper.unlockInventory(invite.getWarehouseId(), a.getProductId(), 1);
            inventoryMapper.deductInventory(invite.getWarehouseId(), a.getProductId(), 1);
        }

        Activity activity = activityMapper.selectById(invite.getActivityId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceType", "activity");
        result.put("inviteId", invite.getId());
        result.put("status", "PICKED_UP");
        result.put("pickedUpAt", invite.getPickedUpAt());
        if (activity != null) {
            Product p = productMapper.selectById(activity.getProductId());
            result.put("productName", p != null ? p.getName() : "");
            result.put("activityPrice", activity.getActivityPrice());
        }
        return R.ok("核销成功！", result);
    }
}
