package com.linghu.controller;

import com.linghu.annotation.RequireRole;
import com.linghu.common.R;
import com.linghu.mapper.WarehouseMapper;
import com.linghu.entity.Warehouse;
import com.linghu.service.ExternalOrderService;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 外单管理控制器
 * <p>
 * 品牌端接口（角色 2=品牌方）：
 *   POST /api/brand/external/batch/import   批量导入外单
 *   GET  /api/brand/external/order/list     外单列表（分页）
 *   POST /api/brand/external/order/cancel   取消外单
 *   GET  /api/brand/external/batch/list     批次记录（分页）
 * <p>
 * 仓端接口（角色 1=仓主）：
 *   GET  /api/warehouse/external/picking/list      外单列表
 *   POST /api/warehouse/external/picking/start     开始拣货
 *   POST /api/warehouse/external/picking/complete  确认发货
 *   POST /api/warehouse/external/picking/exception 上报异常
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ExternalOrderController {

    private final ExternalOrderService externalOrderService;
    private final WarehouseMapper warehouseMapper;

    // =========================================================================
    // 品牌端接口
    // =========================================================================

    /**
     * 品牌端：批量导入外单
     * <p>
     * 请求体示例：
     * {
     *   "fileName": "orders_20240101.xlsx",
     *   "orders": [
     *     {
     *       "externalOrderNo": "TB20240101001",
     *       "channel": "淘宝",
     *       "productName": "商品A",
     *       "skuCode": "SKU001",
     *       "quantity": 2,
     *       "receiverName": "张三",
     *       "receiverPhone": "13800138000",
     *       "receiverAddress": "广东省广州市天河区xxx"
     *     }
     *   ]
     * }
     */
    @RequireRole(2)
    @PostMapping("/api/brand/external/batch/import")
    public R<Map<String, Object>> importOrders(@RequestBody Map<String, Object> body) {
        Long operatorId = SecurityUtil.getCurrentUserId();
        if (operatorId == null) {
            return R.fail("用户未登录");
        }

        // 从 token/SecurityContext 获取品牌ID（品牌方账号 userId 即为关联 brandId，
        // 若项目中品牌ID存储于 Brand 表则需通过 BrandMapper 查询，这里直接使用 userId）
        Long brandId = operatorId;

        String fileName = body.get("fileName") != null ? body.get("fileName").toString() : "unknown.xlsx";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) body.get("orders");
        if (orders == null || orders.isEmpty()) {
            return R.fail("导入数据不能为空");
        }

        Map<String, Object> result = externalOrderService.importOrders(brandId, operatorId, fileName, orders);
        return R.ok(result);
    }

    /**
     * 品牌端：获取外单列表（分页）
     *
     * @param status 状态过滤（可选，0=待处理,1=已取消,2=拣货中,3=已发货,4=异常）
     * @param page   页码，默认1
     * @param size   每页条数，默认20
     */
    @RequireRole(2)
    @GetMapping("/api/brand/external/order/list")
    public R<Map<String, Object>> getBrandOrders(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long brandId = SecurityUtil.getCurrentUserId();
        if (brandId == null) {
            return R.fail("用户未登录");
        }

        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 20;

        Map<String, Object> result = externalOrderService.getBrandOrders(brandId, status, page, size);
        return R.ok(result);
    }

    /**
     * 品牌端：取消外单（仅 status=0 可取消）
     * <p>
     * 请求体：{ "orderId": 123 }
     */
    @RequireRole(2)
    @PostMapping("/api/brand/external/order/cancel")
    public R<Map<String, Object>> cancelOrder(@RequestBody Map<String, Object> body) {
        Long brandId = SecurityUtil.getCurrentUserId();
        if (brandId == null) {
            return R.fail("用户未登录");
        }

        Object orderIdObj = body.get("orderId");
        if (orderIdObj == null) {
            return R.fail("orderId不能为空");
        }
        Long orderId;
        try {
            orderId = Long.parseLong(orderIdObj.toString());
        } catch (NumberFormatException e) {
            return R.fail("orderId格式错误");
        }

        Map<String, Object> result = externalOrderService.cancelOrder(brandId, orderId);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        String msg = result.getOrDefault("msg", "").toString();
        return success ? R.ok(msg, result) : R.fail(msg);
    }

    /**
     * 品牌端：获取批次记录（分页）
     *
     * @param page 页码，默认1
     * @param size 每页条数，默认20
     */
    @RequireRole(2)
    @GetMapping("/api/brand/external/batch/list")
    public R<Map<String, Object>> getBatchList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long brandId = SecurityUtil.getCurrentUserId();
        if (brandId == null) {
            return R.fail("用户未登录");
        }

        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 20;

        Map<String, Object> result = externalOrderService.getBatchList(brandId, page, size);
        return R.ok(result);
    }

    // =========================================================================
    // 仓端接口
    // =========================================================================

    /**
     * 仓端：获取外单列表
     * <p>
     * 自动取当前仓主名下第一个仓库，或由前端传入 warehouseId。
     *
     * @param warehouseId 仓库ID（可选，不传时取当前仓主第一个仓）
     * @param status      状态过滤（可选）
     */
    @RequireRole(1)
    @GetMapping("/api/warehouse/external/picking/list")
    public R<Map<String, Object>> getWarehouseOrders(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Integer status) {

        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            return R.fail("用户未登录");
        }

        Long resolvedWarehouseId = resolveWarehouseId(userId, warehouseId);
        if (resolvedWarehouseId == null) {
            return R.fail("未找到仓库信息");
        }

        Map<String, Object> result = externalOrderService.getWarehouseOrders(resolvedWarehouseId, status);
        return R.ok(result);
    }

    /**
     * 仓端：开始拣货（status 0→2）
     * <p>
     * 请求体：{ "orderId": 123, "warehouseId": 456 }
     */
    @RequireRole(1)
    @PostMapping("/api/warehouse/external/picking/start")
    public R<Map<String, Object>> startPicking(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            return R.fail("用户未登录");
        }

        Long warehouseId = getLong(body, "warehouseId");
        Long resolvedWarehouseId = resolveWarehouseId(userId, warehouseId);
        if (resolvedWarehouseId == null) {
            return R.fail("未找到仓库信息");
        }

        Long orderId = getLong(body, "orderId");
        if (orderId == null) {
            return R.fail("orderId不能为空");
        }

        Map<String, Object> result = externalOrderService.startPicking(resolvedWarehouseId, orderId);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        String msg = result.getOrDefault("msg", "").toString();
        return success ? R.ok(msg, result) : R.fail(msg);
    }

    /**
     * 仓端：确认发货（status 2→3）
     * <p>
     * 请求体：{ "orderId": 123, "warehouseId": 456, "logisticsCompany": "顺丰", "logisticsNo": "SF1234567890" }
     */
    @RequireRole(1)
    @PostMapping("/api/warehouse/external/picking/complete")
    public R<Map<String, Object>> completePicking(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            return R.fail("用户未登录");
        }

        Long warehouseId = getLong(body, "warehouseId");
        Long resolvedWarehouseId = resolveWarehouseId(userId, warehouseId);
        if (resolvedWarehouseId == null) {
            return R.fail("未找到仓库信息");
        }

        Map<String, Object> result = externalOrderService.completePicking(resolvedWarehouseId, body);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        String msg = result.getOrDefault("msg", "").toString();
        return success ? R.ok(msg, result) : R.fail(msg);
    }

    /**
     * 仓端：上报异常（status→4）
     * <p>
     * 请求体：{ "orderId": 123, "warehouseId": 456, "exceptionReason": "商品破损" }
     */
    @RequireRole(1)
    @PostMapping("/api/warehouse/external/picking/exception")
    public R<Map<String, Object>> reportException(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            return R.fail("用户未登录");
        }

        Long warehouseId = getLong(body, "warehouseId");
        Long resolvedWarehouseId = resolveWarehouseId(userId, warehouseId);
        if (resolvedWarehouseId == null) {
            return R.fail("未找到仓库信息");
        }

        Map<String, Object> result = externalOrderService.reportException(resolvedWarehouseId, body);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        String msg = result.getOrDefault("msg", "").toString();
        return success ? R.ok(msg, result) : R.fail(msg);
    }

    // =========================================================================
    // 私有工具方法
    // =========================================================================

    /**
     * 解析仓库ID：优先使用传入值，否则取仓主名下第一个仓库
     */
    private Long resolveWarehouseId(Long userId, Long warehouseId) {
        if (warehouseId != null) {
            // 校验该仓库确实属于当前仓主
            Warehouse warehouse = warehouseMapper.selectById(warehouseId);
            if (warehouse != null && userId.equals(warehouse.getUserId()) && warehouse.getDeleted() == 0) {
                return warehouseId;
            }
            return null;
        }
        // 取第一个仓库
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Warehouse> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getUserId, userId)
                        .eq(Warehouse::getDeleted, 0)
                        .last("LIMIT 1");
        Warehouse warehouse = warehouseMapper.selectOne(wrapper);
        return warehouse != null ? warehouse.getId() : null;
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
