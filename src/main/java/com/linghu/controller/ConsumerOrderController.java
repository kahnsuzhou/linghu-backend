package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.dto.CreateOrderDTO;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.service.WebSocketService;
import com.linghu.util.SecurityUtil;
import org.springframework.context.ApplicationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 消费者订单控制器（指令5 - 订单相关）
 */
@Slf4j
@RestController
@RequestMapping("/api/consumer/order")
@RequiredArgsConstructor
public class ConsumerOrderController {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final InventoryMapper inventoryMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WarehouseMapper warehouseMapper;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;
    private final ApplicationContext applicationContext;
    private final UserAddressMapper userAddressMapper;

    /**
     * 创建订单（支持库存不足时自动拆单到附近仓库）
     * POST /api/consumer/order/create
     *
     * 拆单逻辑：
     * 1. 尝试从指定仓库锁定所需数量的库存
     * 2. 若库存不足，计算缺口，按距离从近到远在其他仓库补货
     * 3. 若所有仓库库存加起来仍不足，抛出异常
     * 4. 每个 (仓库, 商品) 对生成一个 OrderItem，payCallback 会据此分别创建 WorkOrder
     */
    @PostMapping("/create")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderDTO dto) throws JsonProcessingException {
        Long userId = SecurityUtil.getCurrentUserId();

        BigDecimal totalAmount = BigDecimal.ZERO;
        // 拆单后的实际 OrderItem 列表：key=(warehouseId, productId)，value=数量/价格等
        List<Map<String, Object>> resolvedItems = new ArrayList<>();
        // 用于响应的拆单说明
        List<Map<String, Object>> splitInfo = new ArrayList<>();

        for (CreateOrderDTO.OrderItemDTO itemDto : dto.getItems()) {
            Product product = productMapper.selectById(itemDto.getProductId());
            if (product == null || product.getStatus() == 0) {
                throw new BusinessException("商品不存在或已下架: " + itemDto.getProductId());
            }

            int needed = itemDto.getQuantity(); // 还需要锁的数量

            // --- Step 1: 先尝试从指定仓库锁库存 ---
            int lockedInPrimary = 0;
            if (needed > 0) {
                // 查询指定仓库实际可用库存
                Inventory primaryInv = inventoryMapper.selectOne(
                        new LambdaQueryWrapper<Inventory>()
                                .eq(Inventory::getWarehouseId, itemDto.getWarehouseId())
                                .eq(Inventory::getProductId, itemDto.getProductId())
                                .eq(Inventory::getDeleted, 0));

                int primaryAvail = primaryInv != null
                        ? Math.max(0, primaryInv.getQuantity() - primaryInv.getLockedQuantity())
                        : 0;
                int lockFromPrimary = Math.min(needed, primaryAvail);

                if (lockFromPrimary > 0) {
                    int locked = inventoryMapper.lockInventory(
                            itemDto.getWarehouseId(), itemDto.getProductId(), lockFromPrimary);
                    if (locked > 0) {
                        lockedInPrimary = lockFromPrimary;
                        needed -= lockFromPrimary;

                        Map<String, Object> item = new HashMap<>();
                        item.put("productId", product.getId());
                        item.put("productName", product.getName());
                        item.put("warehouseId", itemDto.getWarehouseId());
                        item.put("quantity", lockFromPrimary);
                        item.put("price", product.getRetailPrice());
                        item.put("brandId", product.getBrandId());
                        item.put("isPrimary", true);
                        resolvedItems.add(item);
                        totalAmount = totalAmount.add(product.getRetailPrice().multiply(BigDecimal.valueOf(lockFromPrimary)));
                    }
                }
            }

            // --- Step 2: 若仍有缺口，从附近其他仓库补货 ---
            boolean wasSplit = false;
            if (needed > 0) {
                // 获取有该商品库存的其他仓库，按距离排序
                List<Inventory> otherInventories = inventoryMapper.selectList(
                        new LambdaQueryWrapper<Inventory>()
                                .eq(Inventory::getProductId, itemDto.getProductId())
                                .ne(Inventory::getWarehouseId, itemDto.getWarehouseId())
                                .gt(Inventory::getQuantity, 0)
                                .eq(Inventory::getDeleted, 0));

                // 获取指定仓库坐标，用于计算距离排序
                Warehouse primaryWarehouse = warehouseMapper.selectById(itemDto.getWarehouseId());
                double refLat = primaryWarehouse != null && primaryWarehouse.getLat() != null
                        ? primaryWarehouse.getLat() : 39.9042;
                double refLng = primaryWarehouse != null && primaryWarehouse.getLng() != null
                        ? primaryWarehouse.getLng() : 116.4074;

                // 过滤有效仓库并按距离排序
                List<Map<String, Object>> candidates = new ArrayList<>();
                for (Inventory inv : otherInventories) {
                    int avail = Math.max(0, inv.getQuantity() - inv.getLockedQuantity());
                    if (avail <= 0) continue;
                    Warehouse w = warehouseMapper.selectById(inv.getWarehouseId());
                    if (w == null || w.getStatus() != 1 || w.getDeleted() != 0) continue;
                    double dist = (w.getLat() != null && w.getLng() != null)
                            ? calculateDistance(refLat, refLng, w.getLat(), w.getLng())
                            : Double.MAX_VALUE;
                    Map<String, Object> c = new HashMap<>();
                    c.put("inventory", inv);
                    c.put("warehouse", w);
                    c.put("available", avail);
                    c.put("distance", dist);
                    candidates.add(c);
                }
                candidates.sort(Comparator.comparingDouble(c -> (Double) c.get("distance")));

                for (Map<String, Object> candidate : candidates) {
                    if (needed <= 0) break;
                    Inventory inv = (Inventory) candidate.get("inventory");
                    Warehouse w = (Warehouse) candidate.get("warehouse");
                    int avail = (int) candidate.get("available");
                    int lockQty = Math.min(needed, avail);

                    int locked = inventoryMapper.lockInventory(w.getId(), itemDto.getProductId(), lockQty);
                    if (locked > 0) {
                        needed -= lockQty;
                        wasSplit = true;

                        Map<String, Object> item = new HashMap<>();
                        item.put("productId", product.getId());
                        item.put("productName", product.getName());
                        item.put("warehouseId", w.getId());
                        item.put("warehouseName", w.getName());
                        item.put("quantity", lockQty);
                        item.put("price", product.getRetailPrice());
                        item.put("brandId", product.getBrandId());
                        item.put("isPrimary", false);
                        resolvedItems.add(item);
                        totalAmount = totalAmount.add(product.getRetailPrice().multiply(BigDecimal.valueOf(lockQty)));

                        log.info("拆单补货：商品[{}] 从仓库[{}] 补 {} 件（距离 {:.1f}km）",
                                product.getName(), w.getName(), lockQty, (Double) candidate.get("distance"));
                    }
                }

                if (needed > 0) {
                    // 回滚已锁库存
                    throw new BusinessException(
                            String.format("商品「%s」库存不足，全网仓库合计缺 %d 件，请减少购买数量",
                                    product.getName(), needed));
                }
            }

            // 记录拆单说明
            if (wasSplit || lockedInPrimary < itemDto.getQuantity()) {
                Map<String, Object> info = new HashMap<>();
                info.put("productName", product.getName());
                info.put("totalQty", itemDto.getQuantity());
                info.put("split", true);
                splitInfo.add(info);
            }
        }

        // ==== 运费计算 ====
        // 查询当前用户会员状态
        User consumer = userMapper.selectById(userId);
        boolean isVip = consumer != null
                && consumer.getVipLevel() != null
                && consumer.getVipLevel() > 0
                && consumer.getVipExpireTime() != null
                && consumer.getVipExpireTime().isAfter(LocalDateTime.now());

        String deliveryModeForFee = dto.getDeliveryMode() != null ? dto.getDeliveryMode() : "express";
        BigDecimal shippingFee = BigDecimal.ZERO;
        boolean vipFreeShipping = false;
        if (!"pickup".equals(deliveryModeForFee)) {
            // 常规运费：快逖元，外卖配送 10 元
            BigDecimal baseShipping = "delivery".equals(deliveryModeForFee)
                    ? new BigDecimal("10.00")
                    : new BigDecimal("6.00");
            if (isVip && totalAmount.compareTo(new BigDecimal("30")) >= 0) {
                // 会员且满30元：免运费
                vipFreeShipping = true;
            } else {
                shippingFee = baseShipping;
            }
        }

        BigDecimal goodsAmount = totalAmount;
        BigDecimal finalAmount = goodsAmount.add(shippingFee);

        // 生成订单
        String orderSn = generateOrderSn();
        Order order = new Order();
        order.setOrderSn(orderSn);
        order.setUserId(userId);
        order.setGoodsAmount(goodsAmount);
        order.setShippingFee(shippingFee);
        order.setTotalAmount(finalAmount);
        order.setStatus("PENDING_PAY");
        order.setDeliveryMode(dto.getDeliveryMode() != null ? dto.getDeliveryMode() : "express");
        // 保存收货地址快照
        if (dto.getAddressId() != null) {
            UserAddress addr = userAddressMapper.selectById(dto.getAddressId());
            if (addr != null) {
                order.setDeliveryName(addr.getName());
                order.setDeliveryPhone(addr.getPhone());
                String fullAddr = (addr.getProvince() != null ? addr.getProvince() : "")
                        + (addr.getCity() != null ? addr.getCity() : "")
                        + (addr.getDistrict() != null ? addr.getDistrict() : "")
                        + (addr.getDetail() != null ? addr.getDetail() : "");
                order.setDeliveryAddress(fullAddr);
            }
        }
        order.setDeleted(0);
        orderMapper.insert(order);

        // 创建订单明细（已拆单后的 resolvedItems）
        for (Map<String, Object> item : resolvedItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(((Number) item.get("productId")).longValue());
            orderItem.setBrandId(((Number) item.get("brandId")).longValue());
            orderItem.setWarehouseId(((Number) item.get("warehouseId")).longValue());
            orderItem.setQuantity(((Number) item.get("quantity")).intValue());
            orderItem.setPrice((BigDecimal) item.get("price"));
            orderItemMapper.insert(orderItem);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getId());
        result.put("orderSn", orderSn);
        result.put("goodsAmount", goodsAmount);
        result.put("shippingFee", shippingFee);
        result.put("totalAmount", finalAmount);
        result.put("isVip", isVip);
        result.put("vipFreeShipping", vipFreeShipping);
        result.put("status", "PENDING_PAY");
        result.put("warehouseCount", countDistinctWarehouses(resolvedItems));
        // 是否发生了拆单
        boolean hasSplit = resolvedItems.stream().anyMatch(i -> !(boolean) i.get("isPrimary"));
        result.put("isSplit", hasSplit);
        if (hasSplit) {
            result.put("splitMessage", "部分商品库存不足，已自动从附近仓库补货，将分批发货");
            result.put("splitInfo", splitInfo);
        }

        String msg = hasSplit ? "下单成功（已自动拆单分仓发货）" : "下单成功";
        return R.ok(msg, result);
    }

    /**
     * 模拟支付回调
     * POST /api/consumer/order/pay-callback?orderId={orderId}
     */
    @PostMapping("/pay-callback")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> payCallback(@RequestParam Long orderId) throws JsonProcessingException {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getStatus().equals("PENDING_PAY")) {
            throw new BusinessException("订单状态不正确");
        }

        // 更新订单状态为待发货
        order.setStatus("PENDING_DELIVERY");
        order.setPaidAt(LocalDateTime.now());

        // 自提订单：支付成功时生成自提码
        if ("pickup".equals(order.getDeliveryMode())) {
            String pickUpCode = String.format("%06d", (int)(Math.random() * 1000000));
            order.setPickUpCode(pickUpCode);
        }

        orderMapper.updateById(order);

        // 查询订单明细，按仓库分组创建拣货作业单
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));

        // 按仓库分组（拆单后可能有多个仓库）
        Map<Long, List<OrderItem>> warehouseItems = new HashMap<>();
        for (OrderItem item : items) {
            warehouseItems.computeIfAbsent(item.getWarehouseId(), k -> new ArrayList<>()).add(item);
        }

        int workOrderCount = 0;
        for (Map.Entry<Long, List<OrderItem>> entry : warehouseItems.entrySet()) {
            Long warehouseId = entry.getKey();
            List<OrderItem> warehouseItemList = entry.getValue();

            // 构建作业明细JSON
            List<Map<String, Object>> workItems = new ArrayList<>();
            for (OrderItem item : warehouseItemList) {
                Product product = productMapper.selectById(item.getProductId());
                Map<String, Object> workItem = new HashMap<>();
                workItem.put("productId", item.getProductId());
                workItem.put("productName", product != null ? product.getName() : "未知商品");
                workItem.put("barcode", product != null ? product.getBarcode() : "");
                workItem.put("planQuantity", item.getQuantity());
                workItem.put("scannedQuantity", 0);
                workItems.add(workItem);
            }

            // 创建拣货作业单
            WorkOrder workOrder = new WorkOrder();
            workOrder.setType(2); // 拣货
            workOrder.setWarehouseId(warehouseId);
            workOrder.setBrandId(warehouseItemList.get(0).getBrandId());
            workOrder.setOrderNo(order.getOrderSn());
            workOrder.setDeliveryMode(order.getDeliveryMode());
            workOrder.setStatus("PENDING");
            workOrder.setItems(objectMapper.writeValueAsString(workItems));
            workOrder.setDeleted(0);
            workOrderMapper.insert(workOrder);

            // 更新订单明细关联作业单
            for (OrderItem item : warehouseItemList) {
                item.setWorkOrderId(workOrder.getId());
                orderItemMapper.updateById(item);
            }

            // 通过 WebSocket 通知仓主
            webSocketService.notifyWarehouse(warehouseId, "NEW_PICKING_ORDER", workOrder.getId());
            log.info("已推送拣货通知至仓库: warehouseId={}, workOrderId={}", warehouseId, workOrder.getId());
            workOrderCount++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("orderSn", order.getOrderSn());
        result.put("status", "PENDING_DELIVERY");
        result.put("workOrderCount", workOrderCount);
        result.put("pickUpCode", order.getPickUpCode());  // 自提码（非自提时为null）
        if (workOrderCount > 1) {
            result.put("splitMessage", String.format("订单已拆分至 %d 个仓库，将分批发货", workOrderCount));
        }

        return R.ok("支付成功，等待仓库发货", result);
    }

    /**
     * 我的自提码列表（普通订单，仅返回 deliveryMode=pickup 且已生成自提码的订单）
     * GET /api/consumer/order/pickup-codes
     */
    @GetMapping("/pickup-codes")
    @RequireRole(0)
    public R<List<Map<String, Object>>> getMyOrderPickupCodes() {
        Long userId = SecurityUtil.getCurrentUserId();

        List<Order> pickupOrders = orderMapper.selectList(
            new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getDeleted, 0)
                .eq(Order::getDeliveryMode, "pickup")
                .ne(Order::getStatus, "CANCELLED")   // 取消的不展示，其余全部展示
                .orderByDesc(Order::getCreateTime));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Order order : pickupOrders) {
            // 每个订单的商品和仓库（可能拆单多仓，每仓各显示一条）
            List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));

            // 按仓库分组
            Map<Long, List<OrderItem>> byWarehouse = new HashMap<>();
            for (OrderItem item : items) {
                byWarehouse.computeIfAbsent(item.getWarehouseId(), k -> new ArrayList<>()).add(item);
            }

            for (Map.Entry<Long, List<OrderItem>> entry : byWarehouse.entrySet()) {
                Warehouse w = warehouseMapper.selectById(entry.getKey());
                List<OrderItem> wItems = entry.getValue();

                // 组装商品名称列表
                StringBuilder productNames = new StringBuilder();
                for (OrderItem oi : wItems) {
                    Product p = productMapper.selectById(oi.getProductId());
                    if (productNames.length() > 0) productNames.append("、");
                    productNames.append(p != null ? p.getName() : "商品").append("×").append(oi.getQuantity());
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("orderId", order.getId());
                item.put("orderSn", order.getOrderSn());
                item.put("pickUpCode", order.getPickUpCode() != null ? order.getPickUpCode() : "");
                // 自提订单状态文字：PENDING_DELIVERY/DELIVERING 显示"待核销"，FINISHED 显示"已核销"
                String pickupStatus = order.getStatus();
                String pickupStatusText;
                if ("FINISHED".equals(pickupStatus)) {
                    pickupStatusText = "已核销";
                } else if ("CANCELLED".equals(pickupStatus)) {
                    pickupStatusText = "已取消";
                } else {
                    pickupStatusText = "待核销";
                }
                item.put("status", pickupStatus);
                item.put("statusText", pickupStatusText);
                item.put("orderType", "normal");   // 区分活动订单
                item.put("productName", productNames.toString());
                item.put("totalAmount", order.getTotalAmount());
                item.put("warehouseName", w != null ? w.getName() : "");
                item.put("warehouseAddress", w != null ? w.getAddress() : "");
                item.put("createTime", order.getCreateTime());
                result.add(item);
            }
        }
        return R.ok(result);
    }

    /**
     * 获取订单列表
     * GET /api/consumer/order/list?status=
     */
    @GetMapping("/list")
    @RequireRole(0)
    public R<List<Map<String, Object>>> getOrderList(
            @RequestParam(required = false) String status) {
        Long userId = SecurityUtil.getCurrentUserId();

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getDeleted, 0)
                .orderByDesc(Order::getCreateTime);

        if (status != null && !status.isEmpty()) {
            wrapper.eq(Order::getStatus, status);
        }

        List<Order> orders = orderMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Order order : orders) {
            Map<String, Object> item = new HashMap<>();
            item.put("orderId", order.getId());
            item.put("orderSn", order.getOrderSn());
            item.put("totalAmount", order.getTotalAmount());
            item.put("status", order.getStatus());
            item.put("statusText", getStatusText(order.getStatus()));
            item.put("deliveryMode", order.getDeliveryMode());
            item.put("deliveryName", order.getDeliveryName());
            item.put("deliveryPhone", order.getDeliveryPhone());
            item.put("deliveryAddress", order.getDeliveryAddress());
            item.put("pickUpCode", order.getPickUpCode());  // 自提码
            item.put("createTime", order.getCreateTime());
            // 退款相关字段
            item.put("refundStatus", order.getRefundStatus());
            item.put("refundType", order.getRefundType());
            item.put("refundReason", order.getRefundReason());
            item.put("refundRemark", order.getRefundRemark());
            item.put("refundRequestedAt", order.getRefundRequestedAt());

            // 查询订单明细（最多展示3个）
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()).last("LIMIT 3"));
            List<Map<String, Object>> productSnaps = new ArrayList<>();
            for (OrderItem oi : items) {
                Product p = productMapper.selectById(oi.getProductId());
                Map<String, Object> snap = new HashMap<>();
                snap.put("productName", p != null ? p.getName() : "商品已删除");
                snap.put("quantity", oi.getQuantity());
                snap.put("price", oi.getPrice());
                snap.put("image", p != null ? p.getImages() : null);
                snap.put("sourceType", p != null && p.getSourceType() != null ? p.getSourceType() : "brand");
                productSnaps.add(snap);
            }
            item.put("items", productSnaps);

            // 查询全部 OrderItem（用于拆单判断 + 自提位置）
            List<OrderItem> allItems = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));

            // 是否拆单（该订单涉及多个仓库）
            long distinctWarehouses = allItems.stream().map(OrderItem::getWarehouseId).distinct().count();
            if (distinctWarehouses > 1) {
                item.put("isSplit", true);
                item.put("warehouseCount", distinctWarehouses);
            }

            // 自提订单附带仓库位置（支持拆单多仓导航）
            if ("pickup".equals(order.getDeliveryMode())) {
                List<Map<String, Object>> pickupWarehouses = new ArrayList<>();
                List<Long> warehouseIds = allItems.stream()
                        .map(OrderItem::getWarehouseId).distinct().collect(java.util.stream.Collectors.toList());
                for (Long wid : warehouseIds) {
                    Warehouse w = warehouseMapper.selectById(wid);
                    if (w == null) continue;
                    Map<String, Object> wInfo = new HashMap<>();
                    wInfo.put("warehouseId", w.getId());
                    wInfo.put("name", w.getName());
                    wInfo.put("address", w.getAddress());
                    wInfo.put("lat", w.getLat());
                    wInfo.put("lng", w.getLng());
                    pickupWarehouses.add(wInfo);
                }
                item.put("pickupWarehouses", pickupWarehouses);
            }

            result.add(item);
        }

        return R.ok(result);
    }

    /**
     * 获取订单详情
     * GET /api/consumer/order/detail/{orderId}
     */
    @GetMapping("/detail/{orderId}")
    @RequireRole(0)
    public R<Map<String, Object>> getOrderDetail(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", order.getId());
        detail.put("orderSn", order.getOrderSn());
        detail.put("totalAmount", order.getTotalAmount());
        detail.put("status", order.getStatus());
        detail.put("statusText", getStatusText(order.getStatus()));
        detail.put("deliveryMode", order.getDeliveryMode());
        detail.put("deliveryName", order.getDeliveryName());
        detail.put("deliveryPhone", order.getDeliveryPhone());
        detail.put("deliveryAddress", order.getDeliveryAddress());
        detail.put("pickUpCode", order.getPickUpCode());  // 自提码
        detail.put("logisticsNo", order.getLogisticsNo());
        detail.put("carrier", order.getCarrier());
        detail.put("paidAt", order.getPaidAt());
        detail.put("finishedAt", order.getFinishedAt());
        detail.put("createTime", order.getCreateTime());
        // 退款相关字段
        detail.put("refundStatus", order.getRefundStatus());
        detail.put("refundType", order.getRefundType());
        detail.put("refundReason", order.getRefundReason());
        detail.put("refundRemark", order.getRefundRemark());
        detail.put("refundRequestedAt", order.getRefundRequestedAt());

        // 订单明细
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        List<Map<String, Object>> itemDetails = new ArrayList<>();
        for (OrderItem item : items) {
            Product p = productMapper.selectById(item.getProductId());
            Warehouse w = warehouseMapper.selectById(item.getWarehouseId());
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("productId", item.getProductId());
            itemMap.put("productName", p != null ? p.getName() : "商品已删除");
            itemMap.put("productImage", p != null ? p.getImages() : null);
            itemMap.put("quantity", item.getQuantity());
            itemMap.put("price", item.getPrice());
            itemMap.put("warehouseId", item.getWarehouseId());
            itemMap.put("warehouseName", w != null ? w.getName() : "未知仓库");
            itemMap.put("sourceType", p != null && p.getSourceType() != null ? p.getSourceType() : "brand");
            itemDetails.add(itemMap);
        }
        detail.put("items", itemDetails);

        // 拆单标识
        long distinctWarehouses = items.stream().map(OrderItem::getWarehouseId).distinct().count();
        if (distinctWarehouses > 1) {
            detail.put("isSplit", true);
            detail.put("warehouseCount", distinctWarehouses);
            detail.put("splitTip", String.format("本订单由 %d 个仓库分批发货", distinctWarehouses));
        }

        // 作业单状态（拣货进度）
        if (order.getStatus().equals("PENDING_DELIVERY")) {
            detail.put("pickingStatus", "仓库正在处理");
        }

        return R.ok(detail);
    }

    /**
     * 申请售后退款
     * POST /api/consumer/order/refund/{orderId}
     * Body: { "refundType": "REFUND_ONLY|RETURN", "refundReason": "..." }
     *
     * 允许状态：DELIVERING（待收货）、FINISHED（已完成，7天内）
     */
    @PostMapping("/refund/{orderId}")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> applyRefund(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }

        // 只有待收货、已完成的订单可申请售后
        if (!Arrays.asList("DELIVERING", "FINISHED").contains(order.getStatus())) {
            throw new BusinessException("当前订单状态不支持申请售后");
        }

        // 已完成订单：限7天内申请
        if ("FINISHED".equals(order.getStatus()) && order.getFinishedAt() != null) {
            if (order.getFinishedAt().isBefore(LocalDateTime.now().minusDays(7))) {
                throw new BusinessException("订单完成超过7天，不支持申请售后");
            }
        }

        // 已有进行中的退款申请
        if (order.getRefundStatus() != null && !order.getRefundStatus().equals("REJECTED")) {
            throw new BusinessException("已有退款申请，请等待处理");
        }

        String refundType = body.getOrDefault("refundType", "REFUND_ONLY");
        String refundReason = body.getOrDefault("refundReason", "");
        if (refundReason.isBlank()) {
            throw new BusinessException("请填写退款原因");
        }

        order.setRefundStatus("REQUESTED");
        order.setRefundType(refundType);
        order.setRefundReason(refundReason);
        order.setRefundRequestedAt(LocalDateTime.now());
        order.setRefundRemark(null);
        order.setRefundHandledAt(null);
        orderMapper.updateById(order);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("refundStatus", "REQUESTED");
        result.put("refundType", refundType);
        return R.ok("售后申请提交成功，等待运营处理", result);
    }

    /**
     * 取消退款申请（消费者主动撤销，仅限 REQUESTED 状态）
     * POST /api/consumer/order/refund/{orderId}/cancel
     */
    @PostMapping("/refund/{orderId}/cancel")
    @RequireRole(0)
    public R<String> cancelRefund(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        if (!"REQUESTED".equals(order.getRefundStatus())) {
            throw new BusinessException("当前退款申请无法撤销");
        }
        order.setRefundStatus(null);
        order.setRefundReason(null);
        order.setRefundType(null);
        order.setRefundRequestedAt(null);
        orderMapper.updateById(order);
        return R.ok("已撤销退款申请");
    }

    /**
     * 取消订单
     * POST /api/consumer/order/cancel/{orderId}
     */
    @PostMapping("/cancel/{orderId}")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> cancelOrder(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        if (!Arrays.asList("PENDING_PAY", "PENDING_DELIVERY").contains(order.getStatus())) {
            throw new BusinessException("订单已发货，无法取消");
        }

        // 提前记录是否已支付（修改状态前判断）
        boolean isPaid = "PENDING_DELIVERY".equals(order.getStatus());

        // 更新订单状态
        order.setStatus("CANCELLED");
        orderMapper.updateById(order);

        // 释放锁定库存（包括拆单后各仓库的锁定）
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            inventoryMapper.unlockInventory(item.getWarehouseId(), item.getProductId(), item.getQuantity());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("refunded", false);

        // 若订单已支付（PENDING_DELIVERY），退款到钱包
        if (isPaid && order.getTotalAmount() != null
                && order.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
            try {
                WalletController walletController = applicationContext.getBean(WalletController.class);
                com.linghu.mapper.WalletMapper walletMapper = applicationContext.getBean(com.linghu.mapper.WalletMapper.class);
                walletController.getOrCreateWallet(userId);
                walletMapper.updateBalance(userId, order.getTotalAmount());
                BigDecimal balanceAfter = walletMapper.selectOne(
                        new LambdaQueryWrapper<com.linghu.entity.Wallet>()
                                .eq(com.linghu.entity.Wallet::getUserId, userId)).getBalance();
                walletController.recordTransaction(userId, "REFUND", order.getTotalAmount(),
                        balanceAfter, "订单取消退款 " + order.getOrderSn(), orderId);
                result.put("refunded", true);
                result.put("refundAmount", order.getTotalAmount());
                result.put("balanceAfter", balanceAfter);
                log.info("订单[{}] 取消退款 ¥{} 至用户[{}] 钱包，余额 ¥{}",
                        order.getOrderSn(), order.getTotalAmount(), userId, balanceAfter);
            } catch (Exception e) {
                log.error("订单[{}] 退款失败: {}", order.getOrderSn(), e.getMessage(), e);
                throw new BusinessException("取消成功但退款失败，请联系客服处理");
            }
        }

        return R.ok("取消成功", result);
    }

    /**
     * 确认收货
     * POST /api/consumer/order/confirm/{orderId}
     */
    @PostMapping("/confirm/{orderId}")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Void> confirmReceipt(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        if (!"DELIVERING".equals(order.getStatus())) {
            throw new BusinessException("订单当前状态无法确认收货");
        }
        order.setStatus("FINISHED");
        order.setFinishedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        // ===== 自动分账：确认收货后将款项打入仓主/品牌方钱包 =====
        try {
            autoSettle(order);
        } catch (Exception e) {
            log.warn("订单[{}] 自动分账失败，将在后续补偿处理：{}", orderId, e.getMessage());
            // 分账失败不影响确认收货流程
        }

        return R.ok();
    }

    /**
     * 确认收货后自动分账（正确版）
     *
     * 分账公式：
     *   货款总额       = Σ(price × quantity)
     *   平台佣金       = 货款总额 × 8.5%
     *   仓库服务费     = warehouse.service_fee_rate × 该仓库 OrderItem 条数
     *   品牌方实收     = 货款总额 - 平台佣金 - 仓库服务费（最低为 0）
     *   仓主额外奖励   = 该仓库销售额 × 1%（激励仓主积极性，平台承担）
     */
    private void autoSettle(Order order) {
        // 平台佣金比例 8.5%
        final BigDecimal PLATFORM_RATE = new BigDecimal("0.085");
        // 仓主销售激励比例 1%
        final BigDecimal WAREHOUSE_BONUS_RATE = new BigDecimal("0.01");

        WalletController walletController = applicationContext.getBean(WalletController.class);
        com.linghu.mapper.WalletMapper walletMapper = applicationContext.getBean(com.linghu.mapper.WalletMapper.class);
        com.linghu.mapper.BrandMapper brandMapper = applicationContext.getBean(com.linghu.mapper.BrandMapper.class);

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        if (items == null || items.isEmpty()) return;

        // ── 第一步：按仓库分组，计算仓库服务费，同时汇总每个品牌的货款和已扣费用 ──────────
        // brandDeductions: brandId -> 该品牌订单项所承担的仓库服务费合计
        Map<Long, BigDecimal> brandDeductions = new HashMap<>();
        // brandGoodsAmount: brandId -> 货款合计
        Map<Long, BigDecimal> brandGoodsAmount = new HashMap<>();
        // brandWarehouseItems: brandId -> 所属的 OrderItem（用于计算仓库分组）
        Map<Long, List<OrderItem>> byWarehouse = new HashMap<>();

        for (OrderItem item : items) {
            byWarehouse.computeIfAbsent(item.getWarehouseId(), k -> new ArrayList<>()).add(item);
            BigDecimal itemAmount = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            brandGoodsAmount.merge(item.getBrandId(), itemAmount, BigDecimal::add);
        }

        // 按仓库结算仓主收益
        for (Map.Entry<Long, List<OrderItem>> entry : byWarehouse.entrySet()) {
            Long warehouseId = entry.getKey();
            Warehouse warehouse = warehouseMapper.selectById(warehouseId);
            if (warehouse == null) continue;

            List<OrderItem> warehouseItems = entry.getValue();

            // 固定服务费 = 单价 × 条数
            BigDecimal serviceFee = (warehouse.getServiceFeeRate() != null ? warehouse.getServiceFeeRate() : BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(warehouseItems.size()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            // 该仓库销售额
            BigDecimal warehouseSalesAmount = warehouseItems.stream()
                    .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 仓主销售激励 = 销售额 × 1%（平台补贴，不从品牌方扣）
            BigDecimal salesBonus = warehouseSalesAmount.multiply(WAREHOUSE_BONUS_RATE)
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            // 仓主总收益
            BigDecimal warehouseIncome = serviceFee.add(salesBonus);
            if (warehouseIncome.compareTo(BigDecimal.ZERO) <= 0) continue;

            // 仓库服务费按比例分摊给各品牌
            for (OrderItem item : warehouseItems) {
                BigDecimal itemAmount = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                // 该 item 占该仓库销售额的比例 × 服务费
                BigDecimal itemShare = warehouseSalesAmount.compareTo(BigDecimal.ZERO) > 0
                        ? serviceFee.multiply(itemAmount).divide(warehouseSalesAmount, 2, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                brandDeductions.merge(item.getBrandId(), itemShare, BigDecimal::add);
            }

            // 仓主入账
            Long warehouseUserId = warehouse.getUserId();
            walletController.getOrCreateWallet(warehouseUserId);
            walletMapper.updateBalance(warehouseUserId, warehouseIncome);
            BigDecimal warehouseBalanceAfter = walletMapper.selectOne(
                    new LambdaQueryWrapper<com.linghu.entity.Wallet>()
                            .eq(com.linghu.entity.Wallet::getUserId, warehouseUserId)).getBalance();
            String warehouseRemark = String.format("仓库服务费¥%s + 销售激励¥%s(销售额¥%s×1%%) 订单%s",
                    serviceFee.toPlainString(), salesBonus.toPlainString(),
                    warehouseSalesAmount.toPlainString(), order.getOrderSn());
            walletController.recordTransaction(warehouseUserId, "INCOME", warehouseIncome,
                    warehouseBalanceAfter, warehouseRemark, order.getId());
            log.info("仓主[{}] 订单[{}] 服务费¥{} + 激励¥{} 共¥{} 已到账",
                    warehouseUserId, order.getOrderSn(), serviceFee, salesBonus, warehouseIncome);
        }

        // ── 第二步：按品牌方结算货款（扣除平台佣金和仓库服务费）──────────────────────────
        // 汇总平台本单总佣金
        BigDecimal totalPlatformCommission = BigDecimal.ZERO;

        for (Map.Entry<Long, BigDecimal> entry : brandGoodsAmount.entrySet()) {
            Long brandId = entry.getKey();
            BigDecimal goods = entry.getValue(); // 该品牌货款总额

            // 平台佣金 = 货款 × 8.5%
            BigDecimal platformCommission = goods.multiply(PLATFORM_RATE)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            totalPlatformCommission = totalPlatformCommission.add(platformCommission);

            // 仓库服务费（该品牌应承担部分）
            BigDecimal warehouseFeeForBrand = brandDeductions.getOrDefault(brandId, BigDecimal.ZERO);

            // 品牌方实收 = 货款 - 平台佣金 - 仓库服务费，最低为 0
            BigDecimal brandNet = goods.subtract(platformCommission).subtract(warehouseFeeForBrand);
            if (brandNet.compareTo(BigDecimal.ZERO) < 0) brandNet = BigDecimal.ZERO;

            com.linghu.entity.Brand brand = brandMapper.selectById(brandId);
            if (brand == null) continue;
            Long brandUserId = brand.getUserId();

            walletController.getOrCreateWallet(brandUserId);
            walletMapper.updateBalance(brandUserId, brandNet);
            BigDecimal brandBalanceAfter = walletMapper.selectOne(
                    new LambdaQueryWrapper<com.linghu.entity.Wallet>()
                            .eq(com.linghu.entity.Wallet::getUserId, brandUserId)).getBalance();

            String brandRemark = String.format("货款¥%s - 平台佣金¥%s(%.1f%%) - 仓库服务费¥%s = 实收¥%s 订单%s",
                    goods.toPlainString(), platformCommission.toPlainString(), PLATFORM_RATE.multiply(new BigDecimal("100")).doubleValue(),
                    warehouseFeeForBrand.toPlainString(), brandNet.toPlainString(), order.getOrderSn());
            walletController.recordTransaction(brandUserId, "INCOME", brandNet,
                    brandBalanceAfter, brandRemark, order.getId());
            log.info("品牌方[{}] 订单[{}] 货款¥{} - 平台佣金¥{}(8.5%) - 仓库服务费¥{} = 实收¥{} 已到账",
                    brandUserId, order.getOrderSn(), goods, platformCommission, warehouseFeeForBrand, brandNet);
        }

        // ── 第三步：平台佣金 + 运费入账平台账户（role=9 的 admin）──────────────────────────
        BigDecimal shippingFee = order.getShippingFee() != null ? order.getShippingFee() : BigDecimal.ZERO;
        BigDecimal platformTotal = totalPlatformCommission.add(shippingFee);
        if (platformTotal.compareTo(BigDecimal.ZERO) > 0) {
            com.linghu.mapper.UserMapper userMapper = applicationContext.getBean(com.linghu.mapper.UserMapper.class);
            // 查询平台运营账号（role=9），取第一个
            com.linghu.entity.User platformUser = userMapper.selectOne(
                    new LambdaQueryWrapper<com.linghu.entity.User>()
                            .eq(com.linghu.entity.User::getRole, 9)
                            .eq(com.linghu.entity.User::getDeleted, 0)
                            .orderByAsc(com.linghu.entity.User::getId)
                            .last("LIMIT 1"));
            if (platformUser != null) {
                Long platformUserId = platformUser.getId();
                walletController.getOrCreateWallet(platformUserId);
                walletMapper.updateBalance(platformUserId, platformTotal);
                BigDecimal platformBalanceAfter = walletMapper.selectOne(
                        new LambdaQueryWrapper<com.linghu.entity.Wallet>()
                                .eq(com.linghu.entity.Wallet::getUserId, platformUserId)).getBalance();
                String platformRemark = shippingFee.compareTo(BigDecimal.ZERO) > 0
                        ? String.format("平台佣金(8.5%%)¥%s + 运费¥%s = ¥%s 订单%s",
                                totalPlatformCommission.toPlainString(),
                                shippingFee.toPlainString(),
                                platformTotal.toPlainString(),
                                order.getOrderSn())
                        : String.format("平台佣金(8.5%%) ¥%s 订单%s",
                                totalPlatformCommission.toPlainString(), order.getOrderSn());
                walletController.recordTransaction(platformUserId, "INCOME", platformTotal,
                        platformBalanceAfter, platformRemark, order.getId());
                log.info("平台[userId={}] 订单[{}] 佣金¥{} + 运费¥{} 共¥{} 已入账",
                        platformUserId, order.getOrderSn(), totalPlatformCommission, shippingFee, platformTotal);
            }
        }
    }

    // =================== 工具方法 ===================

    private long countDistinctWarehouses(List<Map<String, Object>> items) {
        return items.stream()
                .map(i -> ((Number) i.get("warehouseId")).longValue())
                .distinct().count();
    }

    /**
     * Haversine 公式计算两点距离（km）
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 生成订单号：LH + yyyyMMddHHmmss + 随机4位
     */
    private String generateOrderSn() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = String.format("%04d", (int) (Math.random() * 10000));
        return "LH" + timestamp + random;
    }

    private String getStatusText(String status) {
        Map<String, String> statusMap = new HashMap<>();
        statusMap.put("PENDING_PAY", "待支付");
        statusMap.put("PENDING_DELIVERY", "待发货");
        statusMap.put("DELIVERING", "待收货");
        statusMap.put("FINISHED", "已完成");
        statusMap.put("CANCELLED", "已取消");
        return statusMap.getOrDefault(status, status);
    }
}
