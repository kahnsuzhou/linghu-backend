package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.Inventory;
import com.linghu.entity.Product;
import com.linghu.entity.Warehouse;
import com.linghu.mapper.InventoryMapper;
import com.linghu.mapper.ProductMapper;
import com.linghu.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.*;

/**
 * 消费者商品控制器（指令5 - 商品相关）
 */
@RestController
@RequestMapping("/api/consumer")
@RequiredArgsConstructor
public class ConsumerProductController {

    private final ProductMapper productMapper;
    private final InventoryMapper inventoryMapper;
    private final WarehouseMapper warehouseMapper;

    /**
     * 获取附近有库存的商品列表
     * GET /api/consumer/products/nearby?lat={lat}&lng={lng}
     */
    @GetMapping("/products/nearby")
    public R<List<Map<String, Object>>> getNearbyProducts(
            @RequestParam(defaultValue = "39.9042") Double lat,
            @RequestParam(defaultValue = "116.4074") Double lng) {

        // 先查 50km 内仓库，若无结果则查所有仓库（保证商品可见）
        List<Warehouse> nearbyWarehouses = warehouseMapper.findNearby(lat, lng, 50.0);
        if (nearbyWarehouses.isEmpty()) {
            nearbyWarehouses = warehouseMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Warehouse>()
                            .eq(Warehouse::getStatus, 1)
                            .eq(Warehouse::getAuditStatus, "APPROVED")
                            .eq(Warehouse::getDeleted, 0));
        }

        // 收集有库存的商品
        List<Map<String, Object>> productList = new ArrayList<>();
        Set<Long> addedProductIds = new HashSet<>();

        for (Warehouse warehouse : nearbyWarehouses) {
            // 查询该仓库有库存的商品
            List<Inventory> inventories = inventoryMapper.selectList(
                    new LambdaQueryWrapper<Inventory>()
                            .eq(Inventory::getWarehouseId, warehouse.getId())
                            .gt(Inventory::getQuantity, 0)
                            .eq(Inventory::getDeleted, 0));

            for (Inventory inventory : inventories) {
                if (addedProductIds.contains(inventory.getProductId())) {
                    continue;
                }

                Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                        .eq(Product::getId, inventory.getProductId())
                        .eq(Product::getStatus, 1)
                        .eq(Product::getDeleted, 0));

                if (product != null) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("productId", product.getId());
                    item.put("name", product.getName());
                    item.put("price", product.getRetailPrice());
                    item.put("images", product.getImages());
                    item.put("barcode", product.getBarcode());
                    item.put("warehouseId", warehouse.getId());
                    item.put("warehouseName", warehouse.getName());
                    item.put("distance", String.format("%.1fkm", calculateDistance(lat, lng, warehouse.getLat(), warehouse.getLng())));
                    item.put("stock", inventory.getQuantity() - inventory.getLockedQuantity());
                    item.put("estimatedDelivery", "30分钟内");
                    item.put("supportedDeliveries", warehouse.getSupportedDeliveries() != null
                            ? warehouse.getSupportedDeliveries() : "[\"express\",\"delivery\",\"pickup\"]");
                    item.put("sourceType", product.getSourceType() != null ? product.getSourceType() : "brand");

                    productList.add(item);
                    addedProductIds.add(product.getId());
                }
            }
        }

        return R.ok(productList);
    }

    /**
     * 获取商品详情
     * GET /api/consumer/product/detail/{productId}
     */
    @GetMapping("/product/detail/{productId}")
    public R<Map<String, Object>> getProductDetail(@PathVariable Long productId) {
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getId, productId)
                .eq(Product::getStatus, 1)
                .eq(Product::getDeleted, 0));

        if (product == null) {
            throw new BusinessException("商品不存在或已下架");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("productId", product.getId());
        detail.put("name", product.getName());
        detail.put("skuCode", product.getSkuCode());
        detail.put("barcode", product.getBarcode());
        detail.put("price", product.getRetailPrice());
        detail.put("images", product.getImages());
        detail.put("weightG", product.getWeightG());
        detail.put("sourceType", product.getSourceType() != null ? product.getSourceType() : "brand");

        // 查询库存（各仓库）
        List<Inventory> inventories = inventoryMapper.selectList(
                new LambdaQueryWrapper<Inventory>()
                        .eq(Inventory::getProductId, productId)
                        .gt(Inventory::getQuantity, 0)
                        .eq(Inventory::getDeleted, 0));

        int totalStock = inventories.stream()
                .mapToInt(i -> Math.max(0, i.getQuantity() - i.getLockedQuantity()))
                .sum();
        detail.put("totalStock", totalStock);

        return R.ok(detail);
    }

    /**
     * 关键词搜索（商品名称 / 条码 / 仓店名称 模糊匹配）
     * GET /api/consumer/products/search?keyword=牛奶&lat=39.9&lng=116.4
     * 返回结构：{ "products": [...], "warehouses": [...] }
     *   - products：按商品名/条码命中，附带最近有库存仓库信息
     *   - warehouses：按仓店名称命中，附带该仓库商品数与距离
     */
    @GetMapping("/products/search")
    public R<Map<String, Object>> searchProducts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "39.9042") Double lat,
            @RequestParam(defaultValue = "116.4074") Double lng) {

        if (keyword == null || keyword.trim().isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("products", Collections.emptyList());
            empty.put("warehouses", Collections.emptyList());
            return R.ok(empty);
        }
        String kw = "%" + keyword.trim() + "%";

        // ── 1. 仓店名称搜索 ──────────────────────────────────────────
        List<Warehouse> allWarehouses = warehouseMapper.selectList(
                new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getStatus, 1)
                        .eq(Warehouse::getAuditStatus, "APPROVED")
                        .eq(Warehouse::getDeleted, 0));

        // 按距离排序（后续商品搜索也复用）
        allWarehouses.sort(Comparator.comparingDouble(
                w -> calculateDistance(lat, lng,
                        w.getLat() == null ? 0 : w.getLat().doubleValue(),
                        w.getLng() == null ? 0 : w.getLng().doubleValue())));

        List<Map<String, Object>> warehouseResults = new ArrayList<>();
        for (Warehouse w : allWarehouses) {
            if (w.getName() == null || !w.getName().contains(keyword.trim())) continue;
            double dist = calculateDistance(lat, lng,
                    w.getLat() == null ? 0 : w.getLat().doubleValue(),
                    w.getLng() == null ? 0 : w.getLng().doubleValue());
            // 统计该仓库在售商品数
            long productCount = inventoryMapper.selectCount(
                    new LambdaQueryWrapper<Inventory>()
                            .eq(Inventory::getWarehouseId, w.getId())
                            .eq(Inventory::getDeleted, 0)
                            .gt(Inventory::getQuantity, 0));
            Map<String, Object> wItem = new LinkedHashMap<>();
            wItem.put("warehouseId", w.getId());
            wItem.put("warehouseName", w.getName());
            wItem.put("address", w.getAddress() != null ? w.getAddress() : "");
            wItem.put("distance", String.format("%.1fkm", dist));
            wItem.put("productCount", productCount);
            wItem.put("supportedDeliveries", w.getSupportedDeliveries() != null
                    ? w.getSupportedDeliveries() : "[\"express\",\"delivery\",\"pickup\"]");
            warehouseResults.add(wItem);
        }

        // ── 2. 商品名称 / 条码搜索 ───────────────────────────────────
        List<Product> matched = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getStatus, 1)
                        .eq(Product::getDeleted, 0)
                        .and(w -> w.like(Product::getName, kw)
                                   .or().like(Product::getBarcode, kw)));

        List<Map<String, Object>> productResults = new ArrayList<>();
        for (Product product : matched) {
            for (Warehouse warehouse : allWarehouses) {
                Inventory inv = inventoryMapper.selectOne(
                        new LambdaQueryWrapper<Inventory>()
                                .eq(Inventory::getWarehouseId, warehouse.getId())
                                .eq(Inventory::getProductId, product.getId())
                                .eq(Inventory::getDeleted, 0));
                if (inv == null) continue;
                int available = inv.getQuantity() - (inv.getLockedQuantity() == null ? 0 : inv.getLockedQuantity());
                if (available <= 0) continue;

                double dist = calculateDistance(lat, lng,
                        warehouse.getLat() == null ? 0 : warehouse.getLat().doubleValue(),
                        warehouse.getLng() == null ? 0 : warehouse.getLng().doubleValue());

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("productId", product.getId());
                item.put("name", product.getName());
                item.put("price", product.getRetailPrice());
                item.put("images", product.getImages());
                item.put("barcode", product.getBarcode());
                item.put("warehouseId", warehouse.getId());
                item.put("warehouseName", warehouse.getName());
                item.put("distance", String.format("%.1fkm", dist));
                item.put("stock", available);
                item.put("estimatedDelivery", "30分钟内");
                item.put("supportedDeliveries", warehouse.getSupportedDeliveries() != null
                        ? warehouse.getSupportedDeliveries() : "[\"express\",\"delivery\",\"pickup\"]");
                item.put("sourceType", product.getSourceType() != null ? product.getSourceType() : "brand");
                productResults.add(item);
                break; // 只取最近仓库
            }
        }
        // 按距离排序
        productResults.sort(Comparator.comparingDouble(m -> {
            String d = (String) m.get("distance");
            try { return Double.parseDouble(d.replace("km", "")); } catch (Exception e) { return 999.0; }
        }));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("products", productResults);
        result.put("warehouses", warehouseResults);
        return R.ok(result);
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
     * 获取仓库地图数据（含经纬度、商品数、库存）
     * GET /api/consumer/warehouses/map?lat={lat}&lng={lng}
     */
    @GetMapping("/warehouses/map")
    public R<List<Map<String, Object>>> getWarehouseMap(
            @RequestParam(defaultValue = "39.9042") Double lat,
            @RequestParam(defaultValue = "116.4074") Double lng) {

        List<Warehouse> warehouses = warehouseMapper.selectList(
                new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getStatus, 1)
                        .eq(Warehouse::getAuditStatus, "APPROVED")
                        .eq(Warehouse::getDeleted, 0));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Warehouse w : warehouses) {
            if (w.getLat() == null || w.getLng() == null) continue;

            // 统计仓库库存总量和商品种类
            List<Inventory> invs = inventoryMapper.selectList(
                    new LambdaQueryWrapper<Inventory>()
                            .eq(Inventory::getWarehouseId, w.getId())
                            .gt(Inventory::getQuantity, 0)
                            .eq(Inventory::getDeleted, 0));

            int totalStock = invs.stream().mapToInt(i -> Math.max(0, i.getQuantity() - i.getLockedQuantity())).sum();
            int productCount = invs.size();

            Map<String, Object> item = new HashMap<>();
            item.put("warehouseId", w.getId());
            item.put("name", w.getName());
            item.put("address", w.getAddress());
            item.put("lat", w.getLat());
            item.put("lng", w.getLng());
            item.put("type", w.getType() != null ? w.getType() : "MINI");
            item.put("areaSqm", w.getAreaSqm());
            item.put("productCount", productCount);
            item.put("totalStock", totalStock);
            item.put("distance", String.format("%.1fkm",
                    calculateDistance(lat, lng, w.getLat().doubleValue(), w.getLng().doubleValue())));
            result.add(item);
        }

        // 按距离排序
        result.sort(Comparator.comparingDouble(m ->
                calculateDistance(lat, lng,
                        ((Number) m.get("lat")).doubleValue(),
                        ((Number) m.get("lng")).doubleValue())));

        return R.ok(result);
    }
}
