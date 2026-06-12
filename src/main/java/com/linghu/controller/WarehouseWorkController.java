package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.dto.CompleteInboundDTO;
import com.linghu.dto.CompletePickingDTO;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.service.WebSocketService;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 仓主作业控制器（指令6）
 */
@Slf4j
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class WarehouseWorkController {

    private final WorkOrderMapper workOrderMapper;
    private final WarehouseMapper warehouseMapper;
    private final InventoryMapper inventoryMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;
    private final BrandMapper brandMapper;
    private final com.linghu.mapper.WalletTransactionMapper walletTransactionMapper;
    private final com.linghu.mapper.WalletMapper walletMapper;

    /**
     * 获取当前仓主名下所有仓库 ID 列表
     */
    private List<Long> getCurrentWarehouseIds() {
        Long userId = SecurityUtil.getCurrentUserId();
        List<Warehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0));
        if (warehouses.isEmpty()) {
            throw new BusinessException("未找到您的仓库信息");
        }
        return warehouses.stream().map(Warehouse::getId).collect(Collectors.toList());
    }

    /**
     * 获取当前仓主的第一个仓库ID（向后兼容，单仓场景使用）
     */
    private Long getCurrentWarehouseId() {
        return getCurrentWarehouseIds().get(0);
    }

    // ==================== 入库作业 ====================

    /**
     * 获取入库作业单列表（当前用户所有仓库）
     * GET /api/warehouse/work/inbound/list
     */
    @GetMapping("/work/inbound/list")
    @RequireRole(1)
    public R<List<Map<String, Object>>> getInboundList() {
        Long userId = SecurityUtil.getCurrentUserId();
        // 查当前仓主名下所有仓库
        List<Warehouse> myWarehouses = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0));
        if (myWarehouses.isEmpty()) {
            throw new BusinessException("未找到您的仓库信息");
        }
        List<Long> warehouseIds = myWarehouses.stream()
                .map(Warehouse::getId).collect(Collectors.toList());

        // 查所有仓库的待处理入库单
        List<WorkOrder> rawList = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getWarehouseId, warehouseIds)
                .eq(WorkOrder::getType, 1)
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                .eq(WorkOrder::getDeleted, 0)
                .orderByDesc(WorkOrder::getCreateTime));

        // 富化：附加品牌名、仓库名、商品汇总
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkOrder wo : rawList) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", wo.getId());
            item.put("type", wo.getType());
            item.put("warehouseId", wo.getWarehouseId());
            item.put("brandId", wo.getBrandId());
            item.put("orderNo", wo.getOrderNo());
            item.put("inboundNo", wo.getInboundNo());
            item.put("status", wo.getStatus());
            item.put("operatorId", wo.getOperatorId());
            item.put("completedAt", wo.getCompletedAt());
            item.put("createTime", wo.getCreateTime());
            item.put("deleted", wo.getDeleted());

            // 仓库名
            Warehouse w = myWarehouses.stream()
                    .filter(wh -> wh.getId().equals(wo.getWarehouseId()))
                    .findFirst().orElse(null);
            item.put("warehouseName", w != null ? w.getName() : "");

            // 品牌名
            String brandName = "";
            if (wo.getBrandId() != null) {
                Brand brand = brandMapper.selectById(wo.getBrandId());
                if (brand != null) brandName = brand.getCompanyName();
            }
            item.put("brandName", brandName);

            // 商品汇总：解析 items JSON，并用 product 表实时刷新 productName（防止历史乱码）
            int itemCount = 0;
            int totalPlanQty = 0;
            try {
                List<Map<String, Object>> items = objectMapper.readValue(
                        wo.getItems(), new TypeReference<List<Map<String, Object>>>() {});
                // 实时用 product 表覆盖 productName
                for (Map<String, Object> it : items) {
                    Object pidObj = it.get("productId");
                    if (pidObj != null) {
                        Product p = productMapper.selectById(((Number) pidObj).longValue());
                        if (p != null) {
                            it.put("productName", p.getName());
                            it.put("sourceType", p.getSourceType() != null ? p.getSourceType() : "brand");
                        }
                    }
                }
                itemCount = items.size();
                totalPlanQty = items.stream()
                        .mapToInt(i -> ((Number) i.getOrDefault("planQuantity", 0)).intValue())
                        .sum();
                item.put("items", items);
            } catch (Exception e) {
                item.put("items", Collections.emptyList());
            }
            item.put("itemCount", itemCount);
            item.put("totalPlanQty", totalPlanQty);

            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 按入库单号查询入库单
     * GET /api/warehouse/work/inbound/query?inboundNo=RKxxxxxxxx
     */
    @GetMapping("/work/inbound/query")
    @RequireRole(1)
    public R<Map<String, Object>> queryByInboundNo(@RequestParam String inboundNo) {
        List<Long> warehouseIds = getCurrentWarehouseIds();

        WorkOrder wo = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getInboundNo, inboundNo)
                .eq(WorkOrder::getType, 1)
                .eq(WorkOrder::getDeleted, 0));

        if (wo == null) {
            return R.fail("入库单号不存在：" + inboundNo);
        }
        if (!warehouseIds.contains(wo.getWarehouseId())) {
            return R.fail("该入库单不属于您的仓库");
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", wo.getId());
        item.put("inboundNo", wo.getInboundNo());
        item.put("status", wo.getStatus());
        item.put("warehouseId", wo.getWarehouseId());
        item.put("brandId", wo.getBrandId());
        item.put("createTime", wo.getCreateTime());

        Warehouse w = warehouseMapper.selectById(wo.getWarehouseId());
        item.put("warehouseName", w != null ? w.getName() : "");

        Brand brand = wo.getBrandId() != null ? brandMapper.selectById(wo.getBrandId()) : null;
        item.put("brandName", brand != null ? brand.getCompanyName() : "");

        try {
            List<Map<String, Object>> items = objectMapper.readValue(
                    wo.getItems(), new TypeReference<List<Map<String, Object>>>() {});
            // 实时刷新 productName，防止历史乱码
            for (Map<String, Object> it : items) {
                Object pidObj = it.get("productId");
                if (pidObj != null) {
                    Product p = productMapper.selectById(((Number) pidObj).longValue());
                    if (p != null) {
                        it.put("productName", p.getName());
                        it.put("sourceType", p.getSourceType() != null ? p.getSourceType() : "brand");
                    }
                }
            }
            item.put("items", items);
            item.put("itemCount", items.size());
            item.put("totalPlanQty", items.stream()
                    .mapToInt(i -> ((Number) i.getOrDefault("planQuantity", 0)).intValue()).sum());
        } catch (Exception e) {
            item.put("items", Collections.emptyList());
            item.put("itemCount", 0);
            item.put("totalPlanQty", 0);
        }
        return R.ok(item);
    }

    /**
     * 开始入库作业
     * POST /api/warehouse/work/inbound/start/{workOrderId}
     */
    @PostMapping("/work/inbound/start/{workOrderId}")
    @RequireRole(1)
    public R<Void> startInbound(@PathVariable Long workOrderId) {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder workOrder = getAndValidateWorkOrder(workOrderId, warehouseIds, 1);

        if (!workOrder.getStatus().equals("PENDING")) {
            throw new BusinessException("作业单状态不允许开始");
        }
        workOrder.setStatus("PROCESSING");
        workOrder.setOperatorId(SecurityUtil.getCurrentUserId());
        workOrderMapper.updateById(workOrder);
        return R.ok();
    }

    /**
     * 完成入库作业
     * POST /api/warehouse/work/inbound/complete
     */
    @PostMapping("/work/inbound/complete")
    @RequireRole(1)
    @Transactional(rollbackFor = Exception.class)
    public R<Void> completeInbound(@RequestBody CompleteInboundDTO dto) {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder workOrder = getAndValidateWorkOrder(dto.getWorkOrderId(), warehouseIds, 1);
        // 入库仓库 ID 取自作业单本身，精确定位
        Long warehouseId = workOrder.getWarehouseId();

        // 更新库存
        for (CompleteInboundDTO.InboundItemDTO item : dto.getItems()) {
            // 查找是否已有库存记录
            Inventory existing = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getWarehouseId, warehouseId)
                    .eq(Inventory::getProductId, item.getProductId())
                    .eq(Inventory::getDeleted, 0));

            if (existing != null) {
                // 增加库存
                inventoryMapper.addInventory(warehouseId, item.getProductId(), item.getActualQuantity());
            } else {
                // 新建库存记录
                Product product = productMapper.selectById(item.getProductId());
                Inventory inventory = new Inventory();
                inventory.setWarehouseId(warehouseId);
                inventory.setProductId(item.getProductId());
                inventory.setBrandId(product != null ? product.getBrandId() : null);
                inventory.setQuantity(item.getActualQuantity());
                inventory.setLockedQuantity(0);
                inventory.setLastInboundAt(LocalDateTime.now());
                inventory.setDeleted(0);
                inventoryMapper.insert(inventory);
            }
        }

        // 完成作业单
        workOrder.setStatus("COMPLETED");
        workOrder.setCompletedAt(LocalDateTime.now());
        workOrderMapper.updateById(workOrder);

        return R.ok();
    }

    // ==================== 拣货作业 ====================

    /**
     * 获取拣货作业单列表
     * GET /api/warehouse/work/picking/list
     */
    @GetMapping("/work/picking/list")
    @RequireRole(1)
    public R<List<Map<String, Object>>> getPickingList() {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        List<WorkOrder> list = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getWarehouseId, warehouseIds)
                .eq(WorkOrder::getType, 2)
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                .eq(WorkOrder::getDeleted, 0)
                .orderByDesc(WorkOrder::getCreateTime));

        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkOrder wo : list) {
            Map<String, Object> item = new HashMap<>();
            item.put("workOrderId", wo.getId());
            item.put("orderNo", wo.getOrderNo());
            item.put("status", wo.getStatus());
            item.put("deliveryMode", wo.getDeliveryMode() != null ? wo.getDeliveryMode() : "express");
            item.put("createTime", wo.getCreateTime());
            try {
                List<Map<String, Object>> items = objectMapper.readValue(wo.getItems(), new TypeReference<List<Map<String, Object>>>() {});
                int totalQty = items.stream().mapToInt(i -> (int) i.get("planQuantity")).sum();
                int scannedQty = items.stream().mapToInt(i -> ((Number) i.getOrDefault("scannedQuantity", 0)).intValue()).sum();
                item.put("totalQuantity", totalQty);
                item.put("scannedQuantity", scannedQty);
                item.put("itemCount", items.size());
                item.put("items", items); // 商品明细，供拣货详情页直接使用
            } catch (Exception e) {
                item.put("totalQuantity", 0);
                item.put("scannedQuantity", 0);
                item.put("items", Collections.emptyList());
            }
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 获取单个拣货作业单详情（进入拣货页时调用，保证商品列表最新）
     * GET /api/warehouse/work/picking/detail/{workOrderId}
     */
    @GetMapping("/work/picking/detail/{workOrderId}")
    @RequireRole(1)
    public R<Map<String, Object>> getPickingDetail(@PathVariable Long workOrderId) throws com.fasterxml.jackson.core.JsonProcessingException {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        if (wo == null || wo.getDeleted() == 1) {
            throw new BusinessException("作业单不存在");
        }
        if (!warehouseIds.contains(wo.getWarehouseId())) {
            throw new BusinessException("无权查看此作业单");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workOrderId", wo.getId());
        result.put("orderNo", wo.getOrderNo());
        result.put("status", wo.getStatus());
        result.put("deliveryMode", wo.getDeliveryMode() != null ? wo.getDeliveryMode() : "express");
        result.put("createTime", wo.getCreateTime());
        List<Map<String, Object>> items = objectMapper.readValue(wo.getItems(), new TypeReference<List<Map<String, Object>>>() {});
        result.put("items", items);
        result.put("itemCount", items.size());
        result.put("totalQuantity", items.stream().mapToInt(i -> (int) i.get("planQuantity")).sum());
        result.put("scannedQuantity", items.stream().mapToInt(i -> ((Number) i.getOrDefault("scannedQuantity", 0)).intValue()).sum());
        return R.ok(result);
    }

    /**
     * 开始拣货
     * POST /api/warehouse/work/picking/start/{workOrderId}
     */
    @PostMapping("/work/picking/start/{workOrderId}")
    @RequireRole(1)
    public R<WorkOrder> startPicking(@PathVariable Long workOrderId) {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder workOrder = getAndValidateWorkOrder(workOrderId, warehouseIds, 2);

        if (!workOrder.getStatus().equals("PENDING")) {
            throw new BusinessException("作业单状态不允许开始");
        }
        workOrder.setStatus("PROCESSING");
        workOrder.setOperatorId(SecurityUtil.getCurrentUserId());
        workOrderMapper.updateById(workOrder);
        return R.ok(workOrder);
    }

    /**
     * 扫码确认商品
     * POST /api/warehouse/work/picking/scan
     */
    @PostMapping("/work/picking/scan")
    @RequireRole(1)
    public R<Map<String, Object>> scanProduct(@RequestBody Map<String, Object> body) throws JsonProcessingException {
        Long workOrderId = Long.valueOf(body.get("workOrderId").toString());
        String barcode = (String) body.get("barcode");
        // 可选：前端可传 productId 精确定位，避免条码重复时混淆
        Long productId = body.get("productId") != null
                ? Long.valueOf(body.get("productId").toString()) : null;

        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder == null || !workOrder.getStatus().equals("PROCESSING")) {
            throw new BusinessException("作业单不存在或未开始");
        }

        // 解析 items
        List<Map<String, Object>> items = objectMapper.readValue(workOrder.getItems(),
                new TypeReference<List<Map<String, Object>>>() {});

        // 检查该条码在拣货单中是否有重复（不同商品共用同一条码）
        if (productId == null) {
            long barcodeMatchCount = items.stream()
                    .filter(i -> barcode.equals(i.get("barcode")))
                    .count();
            if (barcodeMatchCount > 1) {
                // 条码重复，必须由前端传 productId 来精确定位
                List<Map<String, Object>> ambiguous = items.stream()
                        .filter(i -> barcode.equals(i.get("barcode")))
                        .map(i -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("productId", i.get("productId"));
                            m.put("productName", i.get("productName"));
                            return m;
                        })
                        .collect(java.util.stream.Collectors.toList());
                Map<String, Object> errResult = new HashMap<>();
                errResult.put("ambiguous", true);
                errResult.put("barcode", barcode);
                errResult.put("candidates", ambiguous);
                return R.fail("该条码对应多个商品，请选择具体商品后再扫码", errResult);
            }
        }

        boolean found = false;
        for (Map<String, Object> item : items) {
            // 优先用 productId 精确匹配；无 productId 时退回到条码匹配
            boolean match = productId != null
                    ? productId.equals(((Number) item.get("productId")).longValue())
                    : barcode.equals(item.get("barcode"));
            if (!match) continue;

            // 校验条码一致性（防止 productId 与条码不对应）
            if (productId != null && !barcode.equals(item.get("barcode"))) {
                throw new BusinessException("商品条码不匹配，请检查扫描的商品");
            }

            int planQty = (int) item.get("planQuantity");
            int scannedQty = ((Number) item.getOrDefault("scannedQuantity", 0)).intValue();
            if (scannedQty >= planQty) {
                throw new BusinessException("该商品「" + item.get("productName") + "」已扫描完成，无需再扫");
            }
            item.put("scannedQuantity", scannedQty + 1);
            found = true;
            break;
        }

        if (!found) {
            throw new BusinessException("该条码不在当前拣货单中，请检查是否拿错商品");
        }

        // 更新 items
        workOrder.setItems(objectMapper.writeValueAsString(items));
        workOrderMapper.updateById(workOrder);

        // 计算进度
        int total = items.stream().mapToInt(i -> (int) i.get("planQuantity")).sum();
        int scanned = items.stream().mapToInt(i -> ((Number) i.getOrDefault("scannedQuantity", 0)).intValue()).sum();

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("scanned", scanned);
        result.put("completed", scanned >= total);
        result.put("items", items);
        return R.ok(result);
    }

    /**
     * 完成拣货
     * POST /api/warehouse/work/picking/complete
     */
    @PostMapping("/work/picking/complete")
    @RequireRole(1)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> completePicking(@RequestBody CompletePickingDTO dto) throws JsonProcessingException {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder workOrder = getAndValidateWorkOrder(dto.getWorkOrderId(), warehouseIds, 2);
        // 拣货仓库 ID 取自作业单本身
        Long warehouseId = workOrder.getWarehouseId();

        if (!workOrder.getStatus().equals("PROCESSING")) {
            throw new BusinessException("请先开始作业再完成");
        }

        // 获取配送方式
        String deliveryMode = workOrder.getDeliveryMode() != null ? workOrder.getDeliveryMode() : "express";

        // 根据配送方式决定承运商和单号
        String carrier;
        String trackingNo;
        if ("pickup".equals(deliveryMode)) {
            // 到仓自提：生成取货码
            carrier = "到仓自提";
            trackingNo = dto.getTrackingNo() != null ? dto.getTrackingNo() : generatePickupCode();
        } else if ("delivery".equals(deliveryMode)) {
            // 外卖配送：呼叫骑手
            carrier = "外卖骑手";
            trackingNo = dto.getTrackingNo() != null ? dto.getTrackingNo() : generateDeliveryNo();
        } else {
            // 快递配送：选承运商
            carrier = dto.getLogisticsCarrier() != null ? dto.getLogisticsCarrier() : "顺丰速运";
            trackingNo = dto.getTrackingNo() != null ? dto.getTrackingNo() : generateTrackingNo(carrier);
        }

        // 查找关联订单
        String orderSn = workOrder.getOrderNo();
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderSn, orderSn)
                .eq(Order::getDeleted, 0));

        if (order != null) {
            // 只扣减当前仓库负责的 OrderItem（避免拆单时错误操作其他仓库库存）
            List<OrderItem> myItems = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getOrderId, order.getId())
                            .eq(OrderItem::getWarehouseId, warehouseId));
            for (OrderItem item : myItems) {
                int deducted = inventoryMapper.deductInventory(
                        item.getWarehouseId(), item.getProductId(), item.getQuantity());
                if (deducted == 0) {
                    log.warn("扣减库存失败: warehouseId={}, productId={}", item.getWarehouseId(), item.getProductId());
                }
            }

            // 检查该订单所有拣货作业单是否全部完成（支持拆单多仓发货）
            // 注意：排除当前正在完成的作业单自身（它尚未被标记 COMPLETED，但已完成处理）
            long pendingWorkOrders = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getOrderNo, order.getOrderSn())
                    .eq(WorkOrder::getType, 2)
                    .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                    .ne(WorkOrder::getId, workOrder.getId())
                    .eq(WorkOrder::getDeleted, 0));

            if (pendingWorkOrders == 0) {
                // 所有仓库均已发货，更新订单状态为待收货
                order.setStatus("DELIVERING");
                order.setLogisticsNo(trackingNo);
                order.setCarrier(carrier);
                orderMapper.updateById(order);
                // 通知消费者
                webSocketService.notifyUserOrderUpdate(order.getUserId(), order.getId(), "DELIVERING");
                log.info("订单[{}] 所有仓库已发货，状态更新为 DELIVERING", order.getOrderSn());
            } else {
                // 还有其他仓库未发货，仅记录本仓物流信息，订单保持 PENDING_DELIVERY
                log.info("订单[{}] 仍有 {} 个作业单未完成，等待其他仓库发货", order.getOrderSn(), pendingWorkOrders);
            }

            // 创建分账记录（简化版）
            // 仓主服务费
            Warehouse warehouse = warehouseMapper.selectById(warehouseId);
            if (warehouse != null) {
                com.linghu.entity.Settlement settlement = new com.linghu.entity.Settlement();
                settlement.setOrderId(order.getId());
                settlement.setTargetType(2); // 仓主
                settlement.setTargetId(warehouseId);
                settlement.setAmount(warehouse.getServiceFeeRate());
                settlement.setStatus("PENDING");
            }
        }

        // 完成作业单
        workOrder.setStatus("COMPLETED");
        workOrder.setCompletedAt(LocalDateTime.now());
        workOrderMapper.updateById(workOrder);

        Map<String, Object> result = new HashMap<>();
        result.put("trackingNo", trackingNo);
        result.put("carrier", carrier);
        result.put("deliveryMode", deliveryMode);
        result.put("orderStatus", "DELIVERING");

        return R.ok("拣货完成，已通知消费者", result);
    }

    /**
     * 查看当前仓库存
     * GET /api/warehouse/inventory/list
     */
    @GetMapping("/inventory/list")
    @RequireRole(1)
    public R<List<Map<String, Object>>> getInventoryList() {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        List<Inventory> inventories = inventoryMapper.selectList(
                new LambdaQueryWrapper<Inventory>()
                        .in(Inventory::getWarehouseId, warehouseIds)
                        .eq(Inventory::getDeleted, 0));

        // 预加载仓库信息，用于多仓库时在每条库存记录上标注仓库名
        Map<Long, Warehouse> warehouseMap = warehouseMapper.selectBatchIds(warehouseIds)
                .stream().collect(Collectors.toMap(Warehouse::getId, w -> w));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Inventory inv : inventories) {
            Product product = productMapper.selectById(inv.getProductId());
            Warehouse wh = warehouseMap.get(inv.getWarehouseId());
            Map<String, Object> item = new HashMap<>();
            item.put("productId", inv.getProductId());
            item.put("productName", product != null ? product.getName() : "未知商品");
            item.put("barcode", product != null ? product.getBarcode() : "");
            item.put("warehouseId", inv.getWarehouseId());
            item.put("warehouseName", wh != null ? wh.getName() : "");
            item.put("quantity", inv.getQuantity());
            item.put("lockedQuantity", inv.getLockedQuantity());
            item.put("availableQuantity", Math.max(0, inv.getQuantity() - inv.getLockedQuantity()));
            item.put("lastInboundAt", inv.getLastInboundAt());
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 收益明细
     * GET /api/warehouse/earnings
     * 从 wallet_transaction 流水表读取真实结算数据，拆分服务费和销售激励
     */
    @GetMapping("/earnings")
    @RequireRole(1)
    public R<Map<String, Object>> getEarnings(@RequestParam(defaultValue = "month") String period) {
        Long userId = SecurityUtil.getCurrentUserId();
        List<Long> warehouseIds = getCurrentWarehouseIds();

        // ── 时间范围 ──────────────────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime;
        switch (period) {
            case "week":  startTime = now.minusWeeks(1); break;
            case "year":  startTime = now.minusYears(1); break;
            default:      startTime = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0); break; // month
        }

        // ── 从钱包流水查当期 INCOME 流水 ────────────────────────────────
        List<com.linghu.entity.WalletTransaction> txList = walletTransactionMapper.selectList(
                new LambdaQueryWrapper<com.linghu.entity.WalletTransaction>()
                        .eq(com.linghu.entity.WalletTransaction::getUserId, userId)
                        .eq(com.linghu.entity.WalletTransaction::getType, "INCOME")
                        .ge(com.linghu.entity.WalletTransaction::getCreateTime, startTime)
                        .orderByDesc(com.linghu.entity.WalletTransaction::getCreateTime));

        // ── 汇总：通过 remark 区分服务费和销售激励 ──────────────────────
        BigDecimal totalServiceFee  = BigDecimal.ZERO;
        BigDecimal totalSalesBonus  = BigDecimal.ZERO;
        BigDecimal totalEarnings    = BigDecimal.ZERO;

        List<Map<String, Object>> details = new ArrayList<>();
        for (com.linghu.entity.WalletTransaction tx : txList) {
            BigDecimal amount = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;
            String remark     = tx.getRemark() != null ? tx.getRemark() : "";

            totalEarnings = totalEarnings.add(amount);

            // remark 示例: "仓库服务费¥5.00 + 销售激励¥1.15(销售额¥115.00×1%) 订单SN123"
            // 用正则提取服务费和激励
            BigDecimal txServiceFee = BigDecimal.ZERO;
            BigDecimal txSalesBonus = BigDecimal.ZERO;
            java.util.regex.Matcher mSvc  = java.util.regex.Pattern.compile("服务费[¥￥]([0-9.]+)").matcher(remark);
            java.util.regex.Matcher mBonus = java.util.regex.Pattern.compile("销售激励[¥￥]([0-9.]+)").matcher(remark);
            if (mSvc.find())   txServiceFee = new BigDecimal(mSvc.group(1));
            if (mBonus.find()) txSalesBonus = new BigDecimal(mBonus.group(1));

            // 如果 remark 没有拆分明细（历史数据），全部算作服务费
            if (txServiceFee.compareTo(BigDecimal.ZERO) == 0 && txSalesBonus.compareTo(BigDecimal.ZERO) == 0) {
                txServiceFee = amount;
            }

            totalServiceFee = totalServiceFee.add(txServiceFee);
            totalSalesBonus = totalSalesBonus.add(txSalesBonus);

            Map<String, Object> detail = new HashMap<>();
            detail.put("id",          tx.getId());
            detail.put("amount",      amount);
            detail.put("serviceFee",  txServiceFee);
            detail.put("salesBonus",  txSalesBonus);
            detail.put("remark",      remark);
            detail.put("createTime",  tx.getCreateTime());
            detail.put("balanceAfter", tx.getBalanceAfter());
            details.add(detail);
        }

        // ── 作业单数（仅统计，不用于计算金额）──────────────────────────
        long completedOrders = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getWarehouseId, warehouseIds)
                .eq(WorkOrder::getType, 2)
                .eq(WorkOrder::getStatus, "COMPLETED")
                .eq(WorkOrder::getDeleted, 0));

        // ── 服务费率（用于展示）──────────────────────────────────────────
        List<Warehouse> myWarehouses = warehouseMapper.selectBatchIds(warehouseIds);
        BigDecimal serviceFeeRate = myWarehouses.stream()
                .map(Warehouse::getServiceFeeRate)
                .filter(r -> r != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, myWarehouses.size())), 2, java.math.RoundingMode.HALF_UP);

        // ── 当前钱包余额 ─────────────────────────────────────────────────
        com.linghu.entity.Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<com.linghu.entity.Wallet>()
                        .eq(com.linghu.entity.Wallet::getUserId, userId));
        BigDecimal walletBalance = wallet != null ? wallet.getBalance() : BigDecimal.ZERO;

        Map<String, Object> result = new HashMap<>();
        result.put("totalOrders",    completedOrders);
        result.put("serviceFeeRate", serviceFeeRate);
        result.put("totalEarnings",  totalEarnings);
        result.put("totalServiceFee", totalServiceFee);
        result.put("totalSalesBonus", totalSalesBonus);
        result.put("walletBalance",  walletBalance);
        result.put("period",         period);
        result.put("details",        details);

        return R.ok(result);
    }

    /**
     * 验证作业单归属和类型（多仓库感知版：校验作业单所属仓库是否在当前用户名下）
     */
    private WorkOrder getAndValidateWorkOrder(Long workOrderId, List<Long> warehouseIds, int type) {
        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder == null || workOrder.getDeleted() == 1) {
            throw new BusinessException("作业单不存在");
        }
        if (!warehouseIds.contains(workOrder.getWarehouseId())) {
            throw new BusinessException("无权操作此作业单");
        }
        if (workOrder.getType() != type) {
            throw new BusinessException("作业单类型不匹配");
        }
        return workOrder;
    }

    /**
     * 生成物流单号（模拟）
     */
    private String generateTrackingNo(String carrier) {
        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("顺丰速运", "SF");
        prefixMap.put("京东物流", "JD");
        prefixMap.put("中通快递", "ZTO");
        prefixMap.put("圆通快递", "YTO");
        String prefix = prefixMap.getOrDefault(carrier, "LH");
        return prefix + System.currentTimeMillis() + String.format("%04d", (int) (Math.random() * 10000));
    }

    private String generatePickupCode() {
        // 6位数字取货码
        return String.format("%06d", (int) (Math.random() * 1000000));
    }

    private String generateDeliveryNo() {
        return "WM" + System.currentTimeMillis() + String.format("%03d", (int) (Math.random() * 1000));
    }

    // ==================== 仓库信息管理 ====================

    /** 获取当前仓主的仓库配额上限 */
    private int getWarehouseQuota(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return 2;
        Integer vip = user.getVipLevel();
        if (vip != null && vip >= 1) return 5;
        return 2;
    }

    /**
     * 获取当前仓主的所有仓库列表 + 配额信息
     * GET /api/warehouse/info/list
     */
    @GetMapping("/info/list")
    @RequireRole(1)
    public R<Map<String, Object>> getWarehouseList() {
        Long userId = SecurityUtil.getCurrentUserId();
        List<Warehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0)
                .orderByAsc(Warehouse::getId));
        int quota = getWarehouseQuota(userId);
        User user = userMapper.selectById(userId);
        int vipLevel = (user != null && user.getVipLevel() != null) ? user.getVipLevel() : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("warehouses", warehouses);
        result.put("count", warehouses.size());
        result.put("quota", quota);
        result.put("vipLevel", vipLevel);
        // 审核统计：前端可据此展示待审核提示
        long pendingAudit = warehouses.stream()
                .filter(w -> "PENDING".equals(w.getAuditStatus())).count();
        long rejectedAudit = warehouses.stream()
                .filter(w -> "REJECTED".equals(w.getAuditStatus())).count();
        result.put("pendingAuditCount", pendingAudit);
        result.put("rejectedAuditCount", rejectedAudit);
        return R.ok(result);
    }

    /**
     * 获取单个仓库详情（向下兼容旧接口）
     * GET /api/warehouse/info
     */
    @GetMapping("/info")
    @RequireRole(1)
    public R<Warehouse> getWarehouseInfo() {
        Long userId = SecurityUtil.getCurrentUserId();
        Warehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0)
                .last("LIMIT 1"));
        return R.ok(warehouse);
    }

    /**
     * 创建仓库（普通仓主≤2，VIP仓主≤5）
     * POST /api/warehouse/info/create
     */
    @PostMapping("/info/create")
    @RequireRole(1)
    public R<Warehouse> createWarehouse(@RequestBody Warehouse req) {
        Long userId = SecurityUtil.getCurrentUserId();
        int quota = getWarehouseQuota(userId);
        // REJECTED状态的仓库不占配额，仓主可以删掉重新申请
        long existing = warehouseMapper.selectCount(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getUserId, userId)
                .ne(Warehouse::getAuditStatus, "REJECTED")
                .eq(Warehouse::getDeleted, 0));
        if (existing >= quota) {
            User user = userMapper.selectById(userId);
            int vip = (user != null && user.getVipLevel() != null) ? user.getVipLevel() : 0;
            if (vip < 1) {
                throw new BusinessException("普通仓主最多创建 " + quota + " 个仓库，升级VIP可创建最多5个");
            } else {
                throw new BusinessException("VIP仓主最多创建 " + quota + " 个仓库，已达上限");
            }
        }

        Warehouse warehouse = new Warehouse();
        warehouse.setUserId(userId);
        warehouse.setName(req.getName());
        warehouse.setAddress(req.getAddress());
        warehouse.setLat(req.getLat());
        warehouse.setLng(req.getLng());
        warehouse.setAreaSqm(req.getAreaSqm());
        warehouse.setType(req.getType() != null ? req.getType() : "MINI");
        warehouse.setServiceFeeRate(req.getServiceFeeRate() != null
                ? req.getServiceFeeRate()
                : new java.math.BigDecimal("2.00"));
        warehouse.setSupportedDeliveries(req.getSupportedDeliveries() != null
                ? req.getSupportedDeliveries()
                : "[\"express\",\"delivery\",\"pickup\"]");
        warehouse.setStatus(0);           // 创建时关闭，审核通过后才开放
        warehouse.setAuditStatus("PENDING"); // 提交审核
        warehouse.setDeleted(0);
        warehouseMapper.insert(warehouse);
        return R.ok(warehouse);
    }

    /**
     * 更新指定仓库信息
     * PUT /api/warehouse/info/update
     */
    @PutMapping("/info/update")
    @RequireRole(1)
    public R<Warehouse> updateWarehouse(@RequestBody Warehouse req) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (req.getId() == null) {
            throw new BusinessException("请指定仓库ID");
        }
        Warehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getId, req.getId())
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0));
        if (warehouse == null) {
            throw new BusinessException("仓库不存在或无权限操作");
        }
        if (req.getName() != null) warehouse.setName(req.getName());
        if (req.getAddress() != null) warehouse.setAddress(req.getAddress());
        if (req.getLat() != null) warehouse.setLat(req.getLat());
        if (req.getLng() != null) warehouse.setLng(req.getLng());
        if (req.getAreaSqm() != null) warehouse.setAreaSqm(req.getAreaSqm());
        if (req.getType() != null) warehouse.setType(req.getType());
        if (req.getServiceFeeRate() != null) warehouse.setServiceFeeRate(req.getServiceFeeRate());
        if (req.getSupportedDeliveries() != null) warehouse.setSupportedDeliveries(req.getSupportedDeliveries());
        warehouseMapper.updateById(warehouse);
        return R.ok(warehouse);
    }

    /**
     * 删除（关闭）仓库
     * DELETE /api/warehouse/info/delete/{warehouseId}
     */
    @DeleteMapping("/info/delete/{warehouseId}")
    @RequireRole(1)
    public R<Void> deleteWarehouse(@PathVariable Long warehouseId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Warehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getId, warehouseId)
                .eq(Warehouse::getUserId, userId)
                .eq(Warehouse::getDeleted, 0));
        if (warehouse == null) {
            throw new BusinessException("仓库不存在或无权限");
        }
        // 检查是否有进行中的订单
        long activeOrders = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getWarehouseId, warehouseId)
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                .eq(WorkOrder::getDeleted, 0));
        if (activeOrders > 0) {
            throw new BusinessException("该仓库有 " + activeOrders + " 个进行中的任务，无法删除");
        }
        warehouseMapper.deleteById(warehouseId);
        return R.ok();
    }

    // ==================== 出库作业（调拨单/退货单） ====================

    /**
     * 获取出库作业单列表（type=6调拨, type=7退货）
     * GET /api/warehouse/work/outbound/list
     */
    @GetMapping("/work/outbound/list")
    @RequireRole(1)
    public R<List<Map<String, Object>>> getOutboundList() {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        List<WorkOrder> list = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getWarehouseId, warehouseIds)
                .in(WorkOrder::getType, 6, 7)
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                .eq(WorkOrder::getDeleted, 0)
                .orderByDesc(WorkOrder::getCreateTime));

        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkOrder wo : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("workOrderId", wo.getId());
            item.put("outboundNo", wo.getOutboundNo());
            item.put("type", wo.getType());
            item.put("typeText", wo.getType() == 6 ? "调拨出库" : "退货出库");
            item.put("status", wo.getStatus());
            item.put("remark", wo.getRemark());
            item.put("createTime", wo.getCreateTime());

            // 仓库名
            Warehouse w = warehouseMapper.selectById(wo.getWarehouseId());
            item.put("warehouseId", wo.getWarehouseId());
            item.put("warehouseName", w != null ? w.getName() : "");

            // 品牌名
            String brandName = "";
            if (wo.getBrandId() != null) {
                com.linghu.entity.Brand brand = brandMapper.selectById(wo.getBrandId());
                if (brand != null) brandName = brand.getCompanyName();
            }
            item.put("brandName", brandName);

            // 商品明细汇总
            try {
                List<Map<String, Object>> items = objectMapper.readValue(
                        wo.getItems(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                int totalQty = items.stream().mapToInt(i -> ((Number) i.getOrDefault("planQuantity", 0)).intValue()).sum();
                item.put("itemCount", items.size());
                item.put("totalPlanQty", totalQty);
                item.put("items", items);
            } catch (Exception e) {
                item.put("itemCount", 0);
                item.put("totalPlanQty", 0);
                item.put("items", Collections.emptyList());
            }
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 开始出库作业（调拨/退货）
     * POST /api/warehouse/work/outbound/start/{workOrderId}
     */
    @PostMapping("/work/outbound/start/{workOrderId}")
    @RequireRole(1)
    public R<Void> startOutbound(@PathVariable Long workOrderId) {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder == null || workOrder.getDeleted() == 1) {
            throw new BusinessException("作业单不存在");
        }
        if (!warehouseIds.contains(workOrder.getWarehouseId())) {
            throw new BusinessException("无权操作此作业单");
        }
        if (workOrder.getType() != 6 && workOrder.getType() != 7) {
            throw new BusinessException("作业单类型不匹配");
        }
        if (!"PENDING".equals(workOrder.getStatus())) {
            throw new BusinessException("作业单状态不允许开始");
        }
        workOrder.setStatus("PROCESSING");
        workOrder.setOperatorId(SecurityUtil.getCurrentUserId());
        workOrderMapper.updateById(workOrder);
        return R.ok();
    }

    /**
     * 完成出库作业（调拨/退货），实际扣减库存并计算仓储费
     * POST /api/warehouse/work/outbound/complete/{workOrderId}
     */
    @PostMapping("/work/outbound/complete/{workOrderId}")
    @RequireRole(1)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> completeOutbound(@PathVariable Long workOrderId) throws com.fasterxml.jackson.core.JsonProcessingException {
        List<Long> warehouseIds = getCurrentWarehouseIds();
        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder == null || workOrder.getDeleted() == 1) {
            throw new BusinessException("作业单不存在");
        }
        if (!warehouseIds.contains(workOrder.getWarehouseId())) {
            throw new BusinessException("无权操作此作业单");
        }
        if (workOrder.getType() != 6 && workOrder.getType() != 7) {
            throw new BusinessException("作业单类型不匹配");
        }
        if (!"PROCESSING".equals(workOrder.getStatus())) {
            throw new BusinessException("请先开始作业再完成");
        }

        Long warehouseId = workOrder.getWarehouseId();
        List<Map<String, Object>> items = objectMapper.readValue(
                workOrder.getItems(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

        java.math.BigDecimal totalStorageFee = java.math.BigDecimal.ZERO;

        for (Map<String, Object> it : items) {
            Long productId = Long.valueOf(it.get("productId").toString());
            int qty = ((Number) it.get("planQuantity")).intValue();

            // 重新查库存，获取最新 lastInboundAt 计算费用
            com.linghu.entity.Inventory inv = inventoryMapper.selectOne(
                    new LambdaQueryWrapper<com.linghu.entity.Inventory>()
                            .eq(com.linghu.entity.Inventory::getWarehouseId, warehouseId)
                            .eq(com.linghu.entity.Inventory::getProductId, productId)
                            .eq(com.linghu.entity.Inventory::getDeleted, 0));

            // 计算仓储操作费（0.2元/件/天，入库后第7天开始计算）
            java.math.BigDecimal storageFee = java.math.BigDecimal.ZERO;
            if (inv != null && inv.getLastInboundAt() != null) {
                long totalDays = java.time.temporal.ChronoUnit.DAYS.between(
                        inv.getLastInboundAt().toLocalDate(), java.time.LocalDate.now());
                long billableDays = Math.max(0, totalDays - 6);
                storageFee = new java.math.BigDecimal("0.20")
                        .multiply(java.math.BigDecimal.valueOf(billableDays))
                        .multiply(java.math.BigDecimal.valueOf(qty))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
            totalStorageFee = totalStorageFee.add(storageFee);
            it.put("actualStorageFee", storageFee);

            // 扣减库存（quantity 和 lockedQuantity 同时减少）
            int deducted = inventoryMapper.deductInventory(warehouseId, productId, qty);
            if (deducted == 0) {
                log.warn("出库扣减库存失败: warehouseId={}, productId={}", warehouseId, productId);
            }
        }

        // 扣品牌方仓储费（从品牌方钱包扣除）
        if (totalStorageFee.compareTo(java.math.BigDecimal.ZERO) > 0) {
            Long brandId = workOrder.getBrandId();
            // 查品牌方 userId
            com.linghu.entity.Brand brand = brandMapper.selectById(brandId);
            if (brand != null) {
                Long brandUserId = brand.getUserId();
                com.linghu.entity.Wallet wallet = walletMapper.selectOne(
                        new LambdaQueryWrapper<com.linghu.entity.Wallet>()
                                .eq(com.linghu.entity.Wallet::getUserId, brandUserId));
                if (wallet != null) {
                    // 扣余额
                    java.math.BigDecimal newBalance = wallet.getBalance().subtract(totalStorageFee);
                    if (newBalance.compareTo(java.math.BigDecimal.ZERO) < 0) {
                        // 余额不足时仍允许出库，但记录负数（可后续补缴）
                        log.warn("品牌方钱包余额不足，当前余额：{}，需扣：{}", wallet.getBalance(), totalStorageFee);
                    }
                    wallet.setBalance(newBalance);
                    walletMapper.updateById(wallet);

                    // 记录流水
                    com.linghu.entity.WalletTransaction tx = new com.linghu.entity.WalletTransaction();
                    tx.setUserId(brandUserId);
                    tx.setType("EXPENSE");
                    tx.setAmount(totalStorageFee.negate());
                    tx.setBalanceAfter(newBalance);
                    String typeText = workOrder.getType() == 6 ? "调拨出库" : "退货出库";
                    tx.setRemark(typeText + "仓储操作费 出库单号：" + workOrder.getOutboundNo());
                    walletTransactionMapper.insert(tx);
                }
            }
        }

        // 更新 items（含实际费用）并完成作业单
        workOrder.setItems(objectMapper.writeValueAsString(items));
        workOrder.setStatus("COMPLETED");
        workOrder.setCompletedAt(java.time.LocalDateTime.now());
        workOrderMapper.updateById(workOrder);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outboundNo", workOrder.getOutboundNo());
        result.put("totalStorageFee", totalStorageFee);
        result.put("typeText", workOrder.getType() == 6 ? "调拨出库" : "退货出库");
        return R.ok("出库完成，仓储操作费已结算", result);
    }
}
