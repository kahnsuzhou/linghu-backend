package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.dto.ReplenishmentPlanDTO;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.service.WebSocketService;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 品牌方控制器（指令7）
 */
@Slf4j
@RestController
@RequestMapping("/api/brand")
@RequiredArgsConstructor
public class BrandController {

    private final BrandMapper brandMapper;
    private final ProductMapper productMapper;
    private final InventoryMapper inventoryMapper;
    private final WarehouseMapper warehouseMapper;
    private final WorkOrderMapper workOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final com.linghu.mapper.WalletMapper walletMapper;
    private final com.linghu.mapper.WalletTransactionMapper walletTransactionMapper;

    /**
     * 获取当前品牌方ID
     */
    private Long getCurrentBrandId() {
        Long userId = SecurityUtil.getCurrentUserId();
        Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                .eq(Brand::getUserId, userId)
                .eq(Brand::getDeleted, 0)
                .last("LIMIT 1"));
        if (brand == null) {
            throw new BusinessException("未找到品牌方信息");
        }
        return brand.getId();
    }

    // ==================== 商品管理 ====================

    /**
     * 上架商品
     * POST /api/brand/product/create
     */
    @PostMapping("/product/create")
    @RequireRole(2)
    public R<Product> createProduct(@RequestBody Product product) {
        Long brandId = getCurrentBrandId();
        // 条码唯一性校验
        if (product.getBarcode() != null && !product.getBarcode().isEmpty()) {
            long barcodeCount = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                    .eq(Product::getBarcode, product.getBarcode())
                    .eq(Product::getDeleted, 0));
            if (barcodeCount > 0) {
                throw new BusinessException("条码「" + product.getBarcode() + "」已被其他商品使用，请检查后重新输入");
            }
        }
        product.setBrandId(brandId);
        product.setStatus(1);
        product.setDeleted(0);
        // 商品来源默认为品牌商品
        if (product.getSourceType() == null || product.getSourceType().isBlank()) {
            product.setSourceType("brand");
        }
        productMapper.insert(product);
        return R.ok(product);
    }

    /**
     * 更新商品
     * PUT /api/brand/product/update/{productId}
     */
    @PutMapping("/product/update/{productId}")
    @RequireRole(2)
    public R<Product> updateProduct(@PathVariable Long productId, @RequestBody Product product) {
        Long brandId = getCurrentBrandId();
        Product existing = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getId, productId)
                .eq(Product::getBrandId, brandId)
                .eq(Product::getDeleted, 0));
        if (existing == null) {
            throw new BusinessException("商品不存在或无权修改");
        }
        // 条码唯一性校验（排除自身）
        if (product.getBarcode() != null && !product.getBarcode().isEmpty()
                && !product.getBarcode().equals(existing.getBarcode())) {
            long barcodeCount = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                    .eq(Product::getBarcode, product.getBarcode())
                    .ne(Product::getId, productId)
                    .eq(Product::getDeleted, 0));
            if (barcodeCount > 0) {
                throw new BusinessException("条码「" + product.getBarcode() + "」已被其他商品使用，请检查后重新输入");
            }
        }
        product.setId(productId);
        product.setBrandId(brandId);
        productMapper.updateById(product);
        return R.ok(product);
    }

    /**
     * 商品列表
     * GET /api/brand/product/list?keyword=&sourceType=
     */
    @GetMapping("/product/list")
    @RequireRole(2)
    public R<List<Product>> getProductList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType) {
        Long brandId = getCurrentBrandId();
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getBrandId, brandId)
                .eq(Product::getDeleted, 0)
                .orderByDesc(Product::getCreateTime);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Product::getName, keyword);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(Product::getSourceType, sourceType);
        }
        List<Product> products = productMapper.selectList(wrapper);
        return R.ok(products);
    }

    /**
     * 上传商品图片
     * POST /api/brand/product/upload-image
     * Content-Type: multipart/form-data，字段名 file
     */
    @PostMapping("/product/upload-image")
    @RequireRole(2)
    public R<Map<String, Object>> uploadProductImage(
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new BusinessException("文件名不合法");
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        if (!Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext)) {
            throw new BusinessException("仅支持 jpg/jpeg/png/gif/webp 格式图片");
        }

        // 生成唯一文件名，保存到固定目录 /workspace/uploads/
        String newFilename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String uploadDir = "/workspace/uploads";
        Path dirPath = Paths.get(uploadDir);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
        Files.write(dirPath.resolve(newFilename), file.getBytes());

        // 只存相对路径，避免绑定到特定域名（隧道重启域名会变）
        String imageUrl = "/uploads/" + newFilename;

        Map<String, Object> result = new HashMap<>();
        result.put("url", imageUrl);
        result.put("filename", newFilename);
        log.info("商品图片上传成功: {}", imageUrl);
        return R.ok(result);
    }

    // ==================== 铺货计划 ====================

    /**
     * 发起铺货计划
     * POST /api/brand/replenishment/plan
     */
    @PostMapping("/replenishment/plan")
    @RequireRole(2)
    public R<Map<String, Object>> createReplenishmentPlan(@RequestBody ReplenishmentPlanDTO dto) throws JsonProcessingException {
        Long brandId = getCurrentBrandId();
        List<Map<String, Object>> createdOrders = new ArrayList<>();

        for (ReplenishmentPlanDTO.WarehouseAllocationDTO allocation : dto.getWarehouseAllocations()) {
            Warehouse warehouse = warehouseMapper.selectById(allocation.getWarehouseId());
            if (warehouse == null) {
                throw new BusinessException("仓库不存在: " + allocation.getWarehouseId());
            }

            // 构建入库作业明细
            List<Map<String, Object>> items = new ArrayList<>();
            if (allocation.getProducts() != null) {
                for (ReplenishmentPlanDTO.ProductAllocationDTO productAlloc : allocation.getProducts()) {
                    Product product = productMapper.selectById(productAlloc.getProductId());
                    if (product == null) continue;

                    Map<String, Object> item = new HashMap<>();
                    item.put("productId", product.getId());
                    item.put("productName", product.getName());
                    item.put("barcode", product.getBarcode());
                    item.put("planQuantity", productAlloc.getQuantity());
                    item.put("actualQuantity", 0);
                    items.add(item);
                }
            } else if (dto.getProductIds() != null) {
                // 平均分配
                int perWarehouseQty = allocation.getQuantity() != null ? allocation.getQuantity() : dto.getTotalQuantity();
                for (Long productId : dto.getProductIds()) {
                    Product product = productMapper.selectById(productId);
                    if (product == null) continue;
                    Map<String, Object> item = new HashMap<>();
                    item.put("productId", productId);
                    item.put("productName", product.getName());
                    item.put("barcode", product.getBarcode());
                    item.put("planQuantity", perWarehouseQty);
                    item.put("actualQuantity", 0);
                    items.add(item);
                }
            }

            // 生成入库单号：RK + 年月日 + 6位随机序号
            String inboundNo = generateInboundNo();

            // 创建入库作业单
            WorkOrder workOrder = new WorkOrder();
            workOrder.setType(1); // 入库
            workOrder.setWarehouseId(allocation.getWarehouseId());
            workOrder.setBrandId(brandId);
            workOrder.setStatus("PENDING");
            workOrder.setInboundNo(inboundNo);
            workOrder.setItems(objectMapper.writeValueAsString(items));
            workOrder.setDeleted(0);
            workOrderMapper.insert(workOrder);

            // WebSocket 通知仓主
            webSocketService.notifyWarehouse(allocation.getWarehouseId(), "NEW_INBOUND_ORDER", workOrder.getId());
            log.info("铺货入库单已创建: warehouseId={}, workOrderId={}, inboundNo={}", allocation.getWarehouseId(), workOrder.getId(), inboundNo);

            // 构建返回数据（包含入库单号）
            Map<String, Object> orderInfo = new LinkedHashMap<>();
            orderInfo.put("workOrderId", workOrder.getId());
            orderInfo.put("inboundNo", inboundNo);
            orderInfo.put("warehouseId", allocation.getWarehouseId());
            orderInfo.put("warehouseName", warehouse.getName());
            orderInfo.put("itemCount", items.size());
            orderInfo.put("totalPlanQty", items.stream()
                    .mapToInt(i -> ((Number) i.getOrDefault("planQuantity", 0)).intValue()).sum());
            createdOrders.add(orderInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", createdOrders);
        result.put("count", createdOrders.size());
        // 单仓库铺货时直接暴露入库单号，方便前端展示
        if (createdOrders.size() == 1) {
            result.put("inboundNo", createdOrders.get(0).get("inboundNo"));
        }
        return R.ok("铺货计划已创建，入库单号已生成", result);
    }

    /**
     * 生成入库单号：RK + 年月日(8位) + 6位随机数字
     * 例：RK202605101234
     */
    private String generateInboundNo() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = (int)(Math.random() * 900000) + 100000;
        return "RK" + date + seq;
    }

    // ==================== 入库进度查询 ====================

    /**
     * 查询品牌方提交的铺货入库单列表及进度
     * GET /api/brand/replenishment/list
     */
    @GetMapping("/replenishment/list")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getReplenishmentList() {
        Long brandId = getCurrentBrandId();

        // 查询该品牌所有入库作业单（type=1）
        List<WorkOrder> workOrders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getBrandId, brandId)
                        .eq(WorkOrder::getType, 1)
                        .eq(WorkOrder::getDeleted, 0)
                        .orderByDesc(WorkOrder::getId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkOrder wo : workOrders) {
            Map<String, Object> item = new HashMap<>();
            item.put("workOrderId", wo.getId());
            item.put("inboundNo", wo.getInboundNo());
            item.put("status", wo.getStatus());
            item.put("statusText", getInboundStatusText(wo.getStatus()));
            item.put("createTime", wo.getCreateTime());
            item.put("completedAt", wo.getCompletedAt());

            // 仓库信息
            Warehouse warehouse = warehouseMapper.selectById(wo.getWarehouseId());
            item.put("warehouseId", wo.getWarehouseId());
            item.put("warehouseName", warehouse != null ? warehouse.getName() : "未知仓库");

            // 解析 items 字段（商品明细）
            if (wo.getItems() != null && !wo.getItems().isEmpty()) {
                try {
                    List<Map> itemList = objectMapper.readValue(wo.getItems(), List.class);
                    item.put("itemCount", itemList.size());
                    int totalQty = 0;
                    List<Map<String, Object>> productDetails = new ArrayList<>();
                    for (Map rawItem : itemList) {
                        Object productIdObj = rawItem.get("productId");
                        // 兼容两种键名：planQuantity（存储格式）和 planQty（旧格式）
                        Object planQtyObj = rawItem.get("planQuantity") != null ? rawItem.get("planQuantity") : rawItem.get("planQty");
                        // 兼容两种键名：actualQuantity（存储格式）和 actualQty（旧格式）
                        Object actualQtyObj = rawItem.get("actualQuantity") != null ? rawItem.get("actualQuantity") : rawItem.get("actualQty");
                        int planQty = planQtyObj != null ? ((Number) planQtyObj).intValue() : 0;
                        int actualQty = actualQtyObj != null ? ((Number) actualQtyObj).intValue() : 0;
                        totalQty += planQty;
                        Map<String, Object> pd = new HashMap<>(rawItem);
                        if (productIdObj != null) {
                            Long productId = ((Number) productIdObj).longValue();
                            Product product = productMapper.selectById(productId);
                            pd.put("productName", product != null ? product.getName() : "未知商品");
                        }
                        pd.put("planQty", planQty);
                        pd.put("actualQty", actualQty);
                        productDetails.add(pd);
                    }
                    item.put("totalPlanQty", totalQty);
                    item.put("products", productDetails);
                } catch (Exception e) {
                    item.put("itemCount", 0);
                    item.put("totalPlanQty", 0);
                    item.put("products", new ArrayList<>());
                }
            } else {
                item.put("itemCount", 0);
                item.put("totalPlanQty", 0);
                item.put("products", new ArrayList<>());
            }

            result.add(item);
        }
        return R.ok(result);
    }

    private String getInboundStatusText(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "PENDING":    return "待入库";
            case "PROCESSING": return "入库中";
            case "COMPLETED":  return "已完成";
            case "CANCELLED":  return "已取消";
            default:           return status;
        }
    }

    // ==================== 库存概览 ====================

    /**
     * 获取所有仓库库存分布（热力图数据）
     * GET /api/brand/inventory/overview
     */
    @GetMapping("/inventory/overview")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getInventoryOverview() {
        Long brandId = getCurrentBrandId();

        List<Inventory> inventories = inventoryMapper.selectList(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getBrandId, brandId)
                .eq(Inventory::getDeleted, 0));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Inventory inv : inventories) {
            Warehouse warehouse = warehouseMapper.selectById(inv.getWarehouseId());
            Product product = productMapper.selectById(inv.getProductId());

            Map<String, Object> item = new HashMap<>();
            item.put("warehouseId", inv.getWarehouseId());
            item.put("warehouseName", warehouse != null ? warehouse.getName() : "未知仓库");
            item.put("lat", warehouse != null ? warehouse.getLat() : null);
            item.put("lng", warehouse != null ? warehouse.getLng() : null);
            item.put("productId", inv.getProductId());
            item.put("productName", product != null ? product.getName() : "未知商品");
            item.put("quantity", inv.getQuantity());
            item.put("lockedQuantity", inv.getLockedQuantity());
            item.put("availableQuantity", Math.max(0, inv.getQuantity() - inv.getLockedQuantity()));
            result.add(item);
        }
        return R.ok(result);
    }

    // ==================== 订单查看 ====================

    /**
     * 获取品牌方相关所有订单
     * GET /api/brand/order/list
     */
    @GetMapping("/order/list")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getBrandOrders(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        Long brandId = getCurrentBrandId();

        // 查询品牌方商品在订单中的明细
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getBrandId, brandId));

        Set<Long> orderIds = new HashSet<>();
        for (OrderItem item : orderItems) {
            orderIds.add(item.getOrderId());
        }

        if (orderIds.isEmpty()) {
            return R.ok(Collections.emptyList());
        }

        List<Order> orders = orderMapper.selectBatchIds(orderIds);
        orders.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> item = new HashMap<>();
            item.put("orderId", order.getId());
            item.put("orderSn", order.getOrderSn());
            item.put("totalAmount", order.getTotalAmount());
            item.put("status", order.getStatus());
            item.put("createTime", order.getCreateTime());
            result.add(item);
        }
        return R.ok(result);
    }

    // ==================== AI 补货建议（MVP 固定返回） ====================

    /**
     * 获取补货建议
     * GET /api/brand/ai/predict-replenishment?warehouseId=&skuId=
     */
    @GetMapping("/ai/predict-replenishment")
    @RequireRole(2)
    public R<Map<String, Object>> predictReplenishment(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long skuId) {
        // MVP 阶段返回固定建议
        Map<String, Object> suggestion = new HashMap<>();
        suggestion.put("warehouseId", warehouseId);
        suggestion.put("skuId", skuId);
        suggestion.put("recommendedQuantity", 100);
        suggestion.put("reason", "根据历史销售数据（最近7天），建议补货100件，预计3天内售罄");
        suggestion.put("confidence", 0.75);
        suggestion.put("nextReplenishDate", "3天后");
        suggestion.put("note", "MVP阶段固定返回，后续接入AI模型");
        return R.ok(suggestion);
    }

    /**
     * 获取所有可用仓库列表（供铺货时选择）
     * GET /api/brand/warehouse/list
     */
    @GetMapping("/warehouse/list")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getWarehouseList() {
        List<Warehouse> warehouses = warehouseMapper.selectList(
                new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getStatus, 1)
                        .eq(Warehouse::getDeleted, 0)
                        .orderByAsc(Warehouse::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Warehouse w : warehouses) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", w.getId());
            item.put("name", w.getName());
            item.put("address", w.getAddress());
            item.put("type", w.getType() != null ? w.getType() : "MINI");
            item.put("areaSqm", w.getAreaSqm());
            item.put("lat", w.getLat());
            item.put("lng", w.getLng());
            item.put("serviceFeeRate", w.getServiceFeeRate());
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 热力图数据：各仓库库存量 + 订单量（品牌方视角）
     * GET /api/brand/heatmap
     */
    @GetMapping("/heatmap")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getHeatmap() {
        Long brandId = SecurityUtil.getCurrentUserId(); // 品牌方userId即brandId

        List<Warehouse> warehouses = warehouseMapper.selectList(
                new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getStatus, 1)
                        .eq(Warehouse::getDeleted, 0));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Warehouse w : warehouses) {
            if (w.getLat() == null || w.getLng() == null) continue;

            // 该仓库内此品牌的库存
            List<Inventory> invs = inventoryMapper.selectList(
                    new LambdaQueryWrapper<Inventory>()
                            .eq(Inventory::getWarehouseId, w.getId())
                            .eq(Inventory::getDeleted, 0));

            int totalInventory = invs.stream().mapToInt(i -> Math.max(0, i.getQuantity())).sum();
            int productCount = invs.size();

            // 该仓库历史订单量
            long orderCount = orderItemMapper.selectCount(
                    new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getWarehouseId, w.getId()));

            Map<String, Object> item = new HashMap<>();
            item.put("warehouseId", w.getId());
            item.put("name", w.getName());
            item.put("address", w.getAddress());
            item.put("lat", w.getLat());
            item.put("lng", w.getLng());
            item.put("type", w.getType() != null ? w.getType() : "MINI");
            item.put("areaSqm", w.getAreaSqm());
            item.put("totalInventory", totalInventory);
            item.put("productCount", productCount);
            item.put("orderCount", orderCount);
            result.add(item);
        }
        return R.ok(result);
    }

    // ==================== 出库管理（调拨单 + 退货单）====================

    /**
     * 品牌方发起调拨单
     * type=6：品牌方主动将某仓商品调出（调往其他仓或清退）
     * POST /api/brand/outbound/transfer
     * Body: { warehouseId, items:[{productId,quantity}], remark }
     */
    @PostMapping("/outbound/transfer")
    @RequireRole(2)
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> createTransferOrder(@RequestBody Map<String, Object> body) throws JsonProcessingException {
        Long brandId = getCurrentBrandId();
        Long warehouseId = Long.valueOf(body.get("warehouseId").toString());
        String remark = body.get("remark") != null ? body.get("remark").toString() : "";

        List<Map<String, Object>> reqItems = (List<Map<String, Object>>) body.get("items");
        if (reqItems == null || reqItems.isEmpty()) {
            throw new com.linghu.common.BusinessException("请选择出库商品");
        }

        // 验证仓库存在且有该品牌的库存
        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new com.linghu.common.BusinessException("仓库不存在");
        }

        // 构建出库明细，校验库存
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalStorageFee = BigDecimal.ZERO;
        for (Map<String, Object> req : reqItems) {
            Long productId = Long.valueOf(req.get("productId").toString());
            int quantity = ((Number) req.get("quantity")).intValue();

            Inventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getWarehouseId, warehouseId)
                    .eq(Inventory::getProductId, productId)
                    .eq(Inventory::getBrandId, brandId)
                    .eq(Inventory::getDeleted, 0));
            if (inv == null) {
                throw new com.linghu.common.BusinessException("该仓库中未找到指定商品的库存");
            }
            int available = Math.max(0, inv.getQuantity() - inv.getLockedQuantity());
            if (quantity > available) {
                Product p = productMapper.selectById(productId);
                throw new com.linghu.common.BusinessException("商品「" + (p != null ? p.getName() : productId) + "」可用库存不足，当前可用：" + available);
            }

            // 计算仓储操作费（0.2元/件/天，入库后第7天开始计算）
            BigDecimal storageFee = calcStorageFee(inv.getLastInboundAt(), quantity);
            totalStorageFee = totalStorageFee.add(storageFee);

            Product product = productMapper.selectById(productId);
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("productId", productId);
            itemMap.put("productName", product != null ? product.getName() : "");
            itemMap.put("barcode", product != null ? product.getBarcode() : "");
            itemMap.put("planQuantity", quantity);
            itemMap.put("actualQuantity", 0);
            itemMap.put("storageFee", storageFee);
            itemMap.put("lastInboundAt", inv.getLastInboundAt() != null ? inv.getLastInboundAt().toString() : null);
            items.add(itemMap);

            // 预锁定库存，防止被其他操作占用
            int locked = inventoryMapper.lockInventory(warehouseId, productId, quantity);
            if (locked == 0) {
                throw new com.linghu.common.BusinessException("商品库存锁定失败，请重试");
            }
        }

        // 生成出库单号
        String outboundNo = generateOutboundNo("DB");

        // 创建调拨出库作业单（type=6）
        WorkOrder workOrder = new WorkOrder();
        workOrder.setType(6);
        workOrder.setWarehouseId(warehouseId);
        workOrder.setBrandId(brandId);
        workOrder.setStatus("PENDING");
        workOrder.setOutboundNo(outboundNo);
        workOrder.setRemark(remark);
        workOrder.setItems(objectMapper.writeValueAsString(items));
        workOrder.setDeleted(0);
        workOrderMapper.insert(workOrder);

        // 通知仓主
        webSocketService.notifyWarehouse(warehouseId, "NEW_OUTBOUND_ORDER", workOrder.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workOrderId", workOrder.getId());
        result.put("outboundNo", outboundNo);
        result.put("warehouseId", warehouseId);
        result.put("warehouseName", warehouse.getName());
        result.put("itemCount", items.size());
        result.put("totalPlanQty", items.stream().mapToInt(i -> ((Number) i.get("planQuantity")).intValue()).sum());
        result.put("totalStorageFee", totalStorageFee);
        return R.ok("调拨出库单已提交，等待仓主操作", result);
    }

    /**
     * 品牌方发起退货单
     * type=7：品牌方将商品从仓库退回
     * POST /api/brand/outbound/return
     * Body: { warehouseId, items:[{productId,quantity}], reason }
     */
    @PostMapping("/outbound/return")
    @RequireRole(2)
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> createReturnOrder(@RequestBody Map<String, Object> body) throws JsonProcessingException {
        Long brandId = getCurrentBrandId();
        Long warehouseId = Long.valueOf(body.get("warehouseId").toString());
        String reason = body.get("reason") != null ? body.get("reason").toString() : "品牌方退货";

        List<Map<String, Object>> reqItems = (List<Map<String, Object>>) body.get("items");
        if (reqItems == null || reqItems.isEmpty()) {
            throw new com.linghu.common.BusinessException("请选择退货商品");
        }

        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new com.linghu.common.BusinessException("仓库不存在");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalStorageFee = BigDecimal.ZERO;
        for (Map<String, Object> req : reqItems) {
            Long productId = Long.valueOf(req.get("productId").toString());
            int quantity = ((Number) req.get("quantity")).intValue();

            Inventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getWarehouseId, warehouseId)
                    .eq(Inventory::getProductId, productId)
                    .eq(Inventory::getBrandId, brandId)
                    .eq(Inventory::getDeleted, 0));
            if (inv == null) {
                throw new com.linghu.common.BusinessException("该仓库中未找到指定商品的库存");
            }
            int available = Math.max(0, inv.getQuantity() - inv.getLockedQuantity());
            if (quantity > available) {
                Product p = productMapper.selectById(productId);
                throw new com.linghu.common.BusinessException("商品「" + (p != null ? p.getName() : productId) + "」可用库存不足，当前可用：" + available);
            }

            // 计算仓储操作费
            BigDecimal storageFee = calcStorageFee(inv.getLastInboundAt(), quantity);
            totalStorageFee = totalStorageFee.add(storageFee);

            Product product = productMapper.selectById(productId);
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("productId", productId);
            itemMap.put("productName", product != null ? product.getName() : "");
            itemMap.put("barcode", product != null ? product.getBarcode() : "");
            itemMap.put("planQuantity", quantity);
            itemMap.put("actualQuantity", 0);
            itemMap.put("storageFee", storageFee);
            itemMap.put("lastInboundAt", inv.getLastInboundAt() != null ? inv.getLastInboundAt().toString() : null);
            items.add(itemMap);

            // 预锁定库存
            int locked = inventoryMapper.lockInventory(warehouseId, productId, quantity);
            if (locked == 0) {
                throw new com.linghu.common.BusinessException("商品库存锁定失败，请重试");
            }
        }

        String outboundNo = generateOutboundNo("TH");

        WorkOrder workOrder = new WorkOrder();
        workOrder.setType(7);
        workOrder.setWarehouseId(warehouseId);
        workOrder.setBrandId(brandId);
        workOrder.setStatus("PENDING");
        workOrder.setOutboundNo(outboundNo);
        workOrder.setRemark(reason);
        workOrder.setItems(objectMapper.writeValueAsString(items));
        workOrder.setDeleted(0);
        workOrderMapper.insert(workOrder);

        webSocketService.notifyWarehouse(warehouseId, "NEW_OUTBOUND_ORDER", workOrder.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workOrderId", workOrder.getId());
        result.put("outboundNo", outboundNo);
        result.put("warehouseId", warehouseId);
        result.put("warehouseName", warehouse.getName());
        result.put("itemCount", items.size());
        result.put("totalPlanQty", items.stream().mapToInt(i -> ((Number) i.get("planQuantity")).intValue()).sum());
        result.put("totalStorageFee", totalStorageFee);
        return R.ok("退货出库单已提交，等待仓主操作", result);
    }

    /**
     * 查询品牌方出库单列表（调拨+退货）
     * GET /api/brand/outbound/list
     */
    @GetMapping("/outbound/list")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getOutboundList() throws JsonProcessingException {
        Long brandId = getCurrentBrandId();

        List<WorkOrder> workOrders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getBrandId, brandId)
                        .in(WorkOrder::getType, 6, 7)
                        .eq(WorkOrder::getDeleted, 0)
                        .orderByDesc(WorkOrder::getId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkOrder wo : workOrders) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("workOrderId", wo.getId());
            item.put("outboundNo", wo.getOutboundNo());
            item.put("type", wo.getType());
            item.put("typeText", wo.getType() == 6 ? "调拨出库" : "退货出库");
            item.put("status", wo.getStatus());
            item.put("statusText", getOutboundStatusText(wo.getStatus()));
            item.put("remark", wo.getRemark());
            item.put("createTime", wo.getCreateTime());
            item.put("completedAt", wo.getCompletedAt());

            Warehouse warehouse = warehouseMapper.selectById(wo.getWarehouseId());
            item.put("warehouseId", wo.getWarehouseId());
            item.put("warehouseName", warehouse != null ? warehouse.getName() : "未知仓库");

            // 解析明细
            if (wo.getItems() != null && !wo.getItems().isEmpty()) {
                try {
                    List<Map<String, Object>> itemList = objectMapper.readValue(
                            wo.getItems(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                    int totalQty = 0;
                    BigDecimal totalFee = BigDecimal.ZERO;
                    for (Map<String, Object> it : itemList) {
                        totalQty += ((Number) it.getOrDefault("planQuantity", 0)).intValue();
                        Object feeObj = it.get("storageFee");
                        if (feeObj != null) {
                            totalFee = totalFee.add(new BigDecimal(feeObj.toString()));
                        }
                    }
                    item.put("itemCount", itemList.size());
                    item.put("totalPlanQty", totalQty);
                    item.put("totalStorageFee", totalFee);
                    item.put("products", itemList);
                } catch (Exception e) {
                    item.put("itemCount", 0);
                    item.put("totalPlanQty", 0);
                    item.put("totalStorageFee", BigDecimal.ZERO);
                    item.put("products", new ArrayList<>());
                }
            } else {
                item.put("itemCount", 0);
                item.put("totalPlanQty", 0);
                item.put("totalStorageFee", BigDecimal.ZERO);
                item.put("products", new ArrayList<>());
            }
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 取消出库单（仅 PENDING 状态可取消）
     * POST /api/brand/outbound/cancel/{workOrderId}
     */
    @PostMapping("/outbound/cancel/{workOrderId}")
    @RequireRole(2)
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Void> cancelOutboundOrder(@PathVariable Long workOrderId) throws JsonProcessingException {
        Long brandId = getCurrentBrandId();
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        if (wo == null || wo.getDeleted() == 1) {
            throw new com.linghu.common.BusinessException("出库单不存在");
        }
        if (!brandId.equals(wo.getBrandId())) {
            throw new com.linghu.common.BusinessException("无权操作此出库单");
        }
        if (!wo.getType().equals(6) && !wo.getType().equals(7)) {
            throw new com.linghu.common.BusinessException("只能取消调拨/退货出库单");
        }
        if (!"PENDING".equals(wo.getStatus())) {
            throw new com.linghu.common.BusinessException("只有待处理状态的出库单可以取消");
        }

        // 释放预锁定的库存
        List<Map<String, Object>> items = objectMapper.readValue(
                wo.getItems(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> it : items) {
            Long productId = Long.valueOf(it.get("productId").toString());
            int qty = ((Number) it.get("planQuantity")).intValue();
            inventoryMapper.unlockInventory(wo.getWarehouseId(), productId, qty);
        }

        wo.setStatus("CANCELLED");
        workOrderMapper.updateById(wo);
        return R.ok();
    }

    /**
     * 查询品牌方某仓库的库存（含仓储费预估，用于出库单填写）
     * GET /api/brand/outbound/inventory?warehouseId=
     */
    @GetMapping("/outbound/inventory")
    @RequireRole(2)
    public R<List<Map<String, Object>>> getOutboundInventory(@RequestParam Long warehouseId) {
        Long brandId = getCurrentBrandId();
        List<Inventory> invList = inventoryMapper.selectList(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWarehouseId, warehouseId)
                .eq(Inventory::getBrandId, brandId)
                .eq(Inventory::getDeleted, 0));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Inventory inv : invList) {
            Product product = productMapper.selectById(inv.getProductId());
            int available = Math.max(0, inv.getQuantity() - inv.getLockedQuantity());
            if (available <= 0) continue;

            // 预估仓储费（按全部可用库存计算，实际出库时按实际数量）
            BigDecimal feePerUnit = calcStorageFeePerUnit(inv.getLastInboundAt());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", inv.getProductId());
            item.put("productName", product != null ? product.getName() : "未知商品");
            item.put("barcode", product != null ? product.getBarcode() : "");
            item.put("quantity", inv.getQuantity());
            item.put("lockedQuantity", inv.getLockedQuantity());
            item.put("availableQuantity", available);
            item.put("lastInboundAt", inv.getLastInboundAt());
            item.put("storageDays", inv.getLastInboundAt() != null
                    ? ChronoUnit.DAYS.between(inv.getLastInboundAt().toLocalDate(), LocalDate.now())
                    : 0);
            item.put("feePerUnitPerDay", new BigDecimal("0.20"));
            item.put("billableDays", calcBillableDays(inv.getLastInboundAt()));
            item.put("feePerUnit", feePerUnit);
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 计算仓储操作费：0.2元/件/天，入库后第7天开始计费（前6天免费）
     */
    private BigDecimal calcStorageFee(LocalDateTime lastInboundAt, int quantity) {
        BigDecimal feePerUnit = calcStorageFeePerUnit(lastInboundAt);
        return feePerUnit.multiply(BigDecimal.valueOf(quantity));
    }

    private BigDecimal calcStorageFeePerUnit(LocalDateTime lastInboundAt) {
        long billableDays = calcBillableDays(lastInboundAt);
        if (billableDays <= 0) return BigDecimal.ZERO;
        return new BigDecimal("0.20").multiply(BigDecimal.valueOf(billableDays)).setScale(2, RoundingMode.HALF_UP);
    }

    private long calcBillableDays(LocalDateTime lastInboundAt) {
        if (lastInboundAt == null) return 0;
        long totalDays = ChronoUnit.DAYS.between(lastInboundAt.toLocalDate(), LocalDate.now());
        // 前6天免费，第7天开始计费
        long billable = totalDays - 6;
        return Math.max(0, billable);
    }

    private String getOutboundStatusText(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "PENDING":    return "待出库";
            case "PROCESSING": return "出库中";
            case "COMPLETED":  return "已完成";
            case "CANCELLED":  return "已取消";
            default:           return status;
        }
    }

    private String generateOutboundNo(String prefix) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = (int)(Math.random() * 900000) + 100000;
        return prefix + date + seq;
    }
}

