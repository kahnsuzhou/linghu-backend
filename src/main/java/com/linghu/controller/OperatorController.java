package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/operator")
@RequiredArgsConstructor
public class OperatorController {

    private final UserMapper userMapper;
    private final WarehouseMapper warehouseMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final InventoryMapper inventoryMapper;
    private final ProductMapper productMapper;
    private final BrandMapper brandMapper;
    private final WalletMapper walletMapper;
    private final WalletTransactionMapper walletTransactionMapper;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationContext applicationContext;
    private final ActivityMapper activityMapper;
    private final ActivityInviteMapper activityInviteMapper;
    private final ExternalOrderMapper externalOrderMapper;
    private final ExternalBatchMapper externalBatchMapper;

    // ==================== 仪表盘 ====================

    @GetMapping("/dashboard/stats")
    @RequireRole(9)
    public R<Map<String, Object>> getDashboardStats() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 今日 GMV
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<Order> todayOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .ge(Order::getCreateTime, todayStart)
                .ne(Order::getStatus, "CANCELLED")
                .eq(Order::getDeleted, 0));
        BigDecimal todayGmv = todayOrders.stream()
                .map(o -> o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.put("todayGmv", todayGmv);
        data.put("todayOrders", todayOrders.size());

        // 总用户数
        long totalUsers = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .in(User::getRole, Arrays.asList(0, 1, 2))
                .eq(User::getDeleted, 0));
        data.put("totalUsers", totalUsers);

        // 新增用户（今日）
        long newUsers = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .ge(User::getCreateTime, todayStart)
                .eq(User::getDeleted, 0));
        data.put("newUsers", newUsers);

        // 活跃仓点
        long activeWarehouses = warehouseMapper.selectCount(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getStatus, 1)
                .eq(Warehouse::getDeleted, 0));
        data.put("activeWarehouses", activeWarehouses);

        // 近7日订单趋势
        List<String> dates = new ArrayList<>();
        List<Long> orderCounts = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            dates.add(d.format(fmt));
            LocalDateTime dayStart = d.atStartOfDay();
            LocalDateTime dayEnd = d.plusDays(1).atStartOfDay();
            long cnt = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .ge(Order::getCreateTime, dayStart)
                    .lt(Order::getCreateTime, dayEnd)
                    .eq(Order::getDeleted, 0));
            orderCounts.add(cnt);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dates", dates);
        trend.put("orders", orderCounts);
        data.put("orderTrend", trend);

        // 仓点位置
        List<Warehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getStatus, 1)
                .eq(Warehouse::getDeleted, 0)
                .isNotNull(Warehouse::getLat));
        List<Map<String, Object>> locations = warehouses.stream().map(w -> {
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("name", w.getName());
            loc.put("lng", w.getLng());
            loc.put("lat", w.getLat());
            loc.put("value", 100);
            return loc;
        }).collect(Collectors.toList());
        data.put("warehouseLocations", locations);

        // 待办事项
        Map<String, Object> pending = new LinkedHashMap<>();
        // 待审核仓库数
        long warehouseAuditCount = warehouseMapper.selectCount(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getAuditStatus, "PENDING")
                .eq(Warehouse::getDeleted, 0));
        pending.put("warehouseAudit", warehouseAuditCount);
        // 待处理退款申请数
        long refundRequestCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getRefundStatus, "REQUESTED")
                .eq(Order::getDeleted, 0));
        pending.put("refundRequests", refundRequestCount);
        // 异常订单（待发货超30分钟）
        LocalDateTime thirtyMinsAgo = LocalDateTime.now().minusMinutes(30);
        long abnormal = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, "PENDING_DELIVERY")
                .lt(Order::getCreateTime, thirtyMinsAgo)
                .eq(Order::getDeleted, 0));
        pending.put("abnormalOrders", abnormal);
        // 外单异常待处理数
        long extException = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>()
                .eq(ExternalOrder::getStatus, 4).eq(ExternalOrder::getDeleted, 0));
        pending.put("externalException", extException);
        data.put("pendingTasks", pending);

        // ── 外单监控数据 ──────────────────────────────────────────────
        Map<String, Object> extStats = new LinkedHashMap<>();
        long extTotal     = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0));
        long extLocked    = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 1));
        long extPicking   = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 2));
        long extShipped   = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 3));
        long extExcept    = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 4));
        long extCancelled = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 5));
        long extBatches   = externalBatchMapper.selectCount(new LambdaQueryWrapper<ExternalBatch>().eq(ExternalBatch::getDeleted, 0));
        // 今日新增外单
        long extToday = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>()
                .ge(ExternalOrder::getCreateTime, todayStart).eq(ExternalOrder::getDeleted, 0));
        // 累计外单服务费
        List<WalletTransaction> extTxList = walletTransactionMapper.selectList(
                new LambdaQueryWrapper<WalletTransaction>()
                        .eq(WalletTransaction::getType, "INCOME")
                        .like(WalletTransaction::getRemark, "外单服务费"));
        BigDecimal totalServiceFee = extTxList.stream()
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 近7日外单导入趋势
        List<Long> extDailyCounts = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            LocalDateTime dayStart = d.atStartOfDay();
            LocalDateTime dayEnd = d.plusDays(1).atStartOfDay();
            long cnt = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>()
                    .ge(ExternalOrder::getCreateTime, dayStart)
                    .lt(ExternalOrder::getCreateTime, dayEnd)
                    .eq(ExternalOrder::getDeleted, 0));
            extDailyCounts.add(cnt);
        }
        extStats.put("total", extTotal);
        extStats.put("locked", extLocked);
        extStats.put("picking", extPicking);
        extStats.put("shipped", extShipped);
        extStats.put("exception", extExcept);
        extStats.put("cancelled", extCancelled);
        extStats.put("batches", extBatches);
        extStats.put("todayNew", extToday);
        extStats.put("totalServiceFee", totalServiceFee);
        extStats.put("dailyTrend", extDailyCounts);
        data.put("externalStats", extStats);

        return R.ok(data);
    }

    // ==================== 用户管理 ====================

    @GetMapping("/user/list")
    @RequireRole(9)
    public R<Map<String, Object>> getUserList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer role,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getDeleted, 0)
                .in(User::getRole, Arrays.asList(0, 1, 2));
        if (role != null) wrapper.eq(User::getRole, role);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, keyword).or().like(User::getPhone, keyword));
        }
        wrapper.orderByDesc(User::getCreateTime);

        IPage<User> pageResult = userMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = pageResult.getRecords().stream().map(u -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.getId());
            item.put("username", u.getUsername());
            item.put("phone", u.getPhone());
            item.put("role", u.getRole());
            item.put("roleLabel", u.getRole() == 0 ? "消费者" : u.getRole() == 1 ? "仓主" : "品牌方");
            item.put("status", u.getStatus());
            item.put("vipLevel", u.getVipLevel());
            item.put("createTime", u.getCreateTime());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    @PutMapping("/user/{id}/status")
    @RequireRole(9)
    public R<String> updateUserStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        int status = ((Number) body.get("status")).intValue();
        user.setStatus(status);
        userMapper.updateById(user);
        return R.ok(status == 1 ? "已启用" : "已禁用");
    }

    // ==================== 仓库管理 ====================

    @GetMapping("/warehouse/list")
    @RequireRole(9)
    public R<Map<String, Object>> getWarehouseList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {

        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getDeleted, 0);
        if (status != null) wrapper.eq(Warehouse::getStatus, status);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Warehouse::getName, keyword).or().like(Warehouse::getAddress, keyword));
        }
        wrapper.orderByDesc(Warehouse::getCreateTime);

        IPage<Warehouse> pageResult = warehouseMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = pageResult.getRecords().stream().map(w -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", w.getId());
            item.put("name", w.getName());
            item.put("address", w.getAddress());
            item.put("type", w.getType());
            item.put("areaSqm", w.getAreaSqm());
            item.put("status", w.getStatus());
            item.put("serviceFeeRate", w.getServiceFeeRate());
            item.put("supportedDeliveries", w.getSupportedDeliveries());
            item.put("createTime", w.getCreateTime());
            // 查仓主信息
            User owner = userMapper.selectById(w.getUserId());
            item.put("ownerName", owner != null ? owner.getUsername() : "");
            item.put("ownerPhone", owner != null ? owner.getPhone() : "");
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    @PutMapping("/warehouse/{id}/status")
    @RequireRole(9)
    public R<String> updateWarehouseStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Warehouse w = warehouseMapper.selectById(id);
        if (w == null) throw new BusinessException("仓库不存在");
        // 未通过审核的仓库不允许手动开放
        if (!"APPROVED".equals(w.getAuditStatus())) {
            throw new BusinessException("仓库尚未通过审核，无法开放");
        }
        int status = ((Number) body.get("status")).intValue();
        w.setStatus(status);
        warehouseMapper.updateById(w);
        return R.ok(status == 1 ? "已开放" : "已关闭");
    }

    // ==================== 仓库审核 ====================

    @GetMapping("/warehouse/audit/list")
    @RequireRole(9)
    public R<Map<String, Object>> getWarehouseAuditList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getDeleted, 0);
        if (auditStatus != null && !auditStatus.isEmpty()) {
            wrapper.eq(Warehouse::getAuditStatus, auditStatus);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Warehouse::getName, keyword).or().like(Warehouse::getAddress, keyword));
        }
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(Warehouse::getCreateTime, java.time.LocalDate.parse(startDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.lt(Warehouse::getCreateTime, java.time.LocalDate.parse(endDate).plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(Warehouse::getCreateTime);

        IPage<Warehouse> pageResult = warehouseMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = pageResult.getRecords().stream().map(w -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", w.getId());
            item.put("warehouseName", w.getName());
            item.put("warehouseAddress", w.getAddress());
            item.put("warehouseType", w.getType() != null ? w.getType().toLowerCase() : "mini");
            item.put("areaSqm", w.getAreaSqm());
            item.put("serviceFeeRate", w.getServiceFeeRate());
            item.put("supportedDeliveries", w.getSupportedDeliveries());
            item.put("auditStatus", w.getAuditStatus() != null ? w.getAuditStatus() : "PENDING");
            item.put("rejectReason", w.getAuditRemark());
            item.put("auditedAt", w.getAuditedAt());
            item.put("auditedBy", w.getAuditedBy());
            item.put("createTime", w.getCreateTime());
            User owner = userMapper.selectById(w.getUserId());
            item.put("ownerName", owner != null ? owner.getUsername() : "");
            item.put("ownerPhone", owner != null ? owner.getPhone() : "");
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    @PutMapping("/warehouse/{id}/audit")
    @RequireRole(9)
    public R<String> auditWarehouse(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Warehouse w = warehouseMapper.selectById(id);
        if (w == null) throw new BusinessException("仓库不存在");
        if (!"PENDING".equals(w.getAuditStatus())) {
            throw new BusinessException("该仓库已审核，无法重复操作");
        }

        String action = (String) body.get("action"); // approve / reject
        String remark = (String) body.getOrDefault("remark", "");

        if ("approve".equals(action)) {
            w.setAuditStatus("APPROVED");
            w.setStatus(1); // 审核通过同时开放
            w.setAuditRemark(null);
            w.setAuditedAt(LocalDateTime.now());
            w.setAuditedBy("admin");
            warehouseMapper.updateById(w);
            return R.ok("审核通过，仓库已上线");
        } else if ("reject".equals(action)) {
            if (remark == null || remark.trim().isEmpty()) {
                throw new BusinessException("拒绝时必须填写原因");
            }
            w.setAuditStatus("REJECTED");
            w.setStatus(0);
            w.setAuditRemark(remark);
            w.setAuditedAt(LocalDateTime.now());
            w.setAuditedBy("admin");
            warehouseMapper.updateById(w);
            return R.ok("已拒绝该申请");
        } else {
            throw new BusinessException("无效的审核操作，请传 approve 或 reject");
        }
    }

    @PutMapping("/warehouse/audit/batch-approve")
    @RequireRole(9)
    public R<String> batchApproveWarehouse(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("请传入仓库ID列表");
        int count = 0;
        for (Integer rawId : ids) {
            Warehouse w = warehouseMapper.selectById(rawId.longValue());
            if (w != null && "PENDING".equals(w.getAuditStatus())) {
                w.setAuditStatus("APPROVED");
                w.setStatus(1);
                w.setAuditedAt(LocalDateTime.now());
                w.setAuditedBy("admin");
                warehouseMapper.updateById(w);
                count++;
            }
        }
        return R.ok("已批量通过 " + count + " 个仓库");
    }

    // ==================== 订单管理 ====================

    @GetMapping("/order/list")
    @RequireRole(9)
    public R<Map<String, Object>> getOrderList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getDeleted, 0);
        if (status != null && !status.isEmpty()) wrapper.eq(Order::getStatus, status);
        if (keyword != null && !keyword.isEmpty()) wrapper.like(Order::getOrderSn, keyword);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(Order::getCreateTime, LocalDate.parse(startDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.lt(Order::getCreateTime, LocalDate.parse(endDate).plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(Order::getCreateTime);

        IPage<Order> pageResult = orderMapper.selectPage(new Page<>(page, size), wrapper);
        LocalDateTime thirtyMinsAgo = LocalDateTime.now().minusMinutes(30);

        List<Map<String, Object>> records = pageResult.getRecords().stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.getId());
            item.put("orderSn", o.getOrderSn());
            item.put("totalAmount", o.getTotalAmount());
            item.put("goodsAmount", o.getGoodsAmount());
            item.put("shippingFee", o.getShippingFee());
            item.put("status", o.getStatus());
            item.put("deliveryMode", o.getDeliveryMode());
            item.put("createTime", o.getCreateTime());
            item.put("paidAt", o.getPaidAt());
            // 用户信息
            User u = userMapper.selectById(o.getUserId());
            item.put("username", u != null ? u.getUsername() : "");
            item.put("userPhone", u != null ? u.getPhone() : "");
            // 异常标记
            boolean isAbnormal = "PENDING_DELIVERY".equals(o.getStatus())
                    && o.getCreateTime() != null && o.getCreateTime().isBefore(thirtyMinsAgo);
            item.put("isAbnormal", isAbnormal);
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    @GetMapping("/order/detail/{orderId}")
    @RequireRole(9)
    public R<Map<String, Object>> getOrderDetail(@PathVariable Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new BusinessException("订单不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.getId());
        result.put("orderSn", order.getOrderSn());
        result.put("status", order.getStatus());
        result.put("totalAmount", order.getTotalAmount());
        result.put("goodsAmount", order.getGoodsAmount());
        result.put("shippingFee", order.getShippingFee());
        result.put("deliveryMode", order.getDeliveryMode());
        result.put("createTime", order.getCreateTime());
        result.put("paidAt", order.getPaidAt());
        result.put("finishedAt", order.getFinishedAt());
        result.put("logisticsNo", order.getLogisticsNo());
        result.put("carrier", order.getCarrier());

        User u = userMapper.selectById(order.getUserId());
        result.put("username", u != null ? u.getUsername() : "");
        result.put("userPhone", u != null ? u.getPhone() : "");

        // 订单商品
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        List<Map<String, Object>> itemList = items.stream().map(oi -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("productId", oi.getProductId());
            im.put("quantity", oi.getQuantity());
            im.put("price", oi.getPrice());
            Product p = productMapper.selectById(oi.getProductId());
            im.put("productName", p != null ? p.getName() : "");
            im.put("images", p != null ? p.getImages() : "");
            return im;
        }).collect(Collectors.toList());
        result.put("items", itemList);

        return R.ok(result);
    }

    // ==================== 结算管理 ====================

    @GetMapping("/settlement/rate")
    @RequireRole(9)
    public R<Map<String, Object>> getSettlementRate() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platformRate", 8.5);
        data.put("warehouseDefaultFee", 3.0);
        data.put("warehouseBonusRate", 1.0);
        return R.ok(data);
    }

    @PutMapping("/settlement/rate")
    @RequireRole(9)
    public R<String> updateSettlementRate(@RequestBody Map<String, Object> body) {
        log.info("更新分账配置: {}", body);
        return R.ok("保存成功");
    }

    @GetMapping("/settlement/records")
    @RequireRole(9)
    public R<Map<String, Object>> getSettlementRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, "FINISHED")
                .eq(Order::getDeleted, 0);
        if (keyword != null && !keyword.isEmpty()) wrapper.like(Order::getOrderSn, keyword);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(Order::getCreateTime, LocalDate.parse(startDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.lt(Order::getCreateTime, LocalDate.parse(endDate).plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(Order::getCreateTime);

        IPage<Order> pageResult = orderMapper.selectPage(new Page<>(page, size), wrapper);
        double platformRate = 0.085;
        double warehouseFeeRate = 3.0;
        double warehouseBonusRate = 0.01;

        List<Map<String, Object>> records = pageResult.getRecords().stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderSn", o.getOrderSn());
            BigDecimal total = o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal goods = o.getGoodsAmount() != null ? o.getGoodsAmount() : BigDecimal.ZERO;
            BigDecimal shipping = o.getShippingFee() != null ? o.getShippingFee() : BigDecimal.ZERO;
            BigDecimal platform = goods.multiply(BigDecimal.valueOf(platformRate)).setScale(2, BigDecimal.ROUND_HALF_UP);
            BigDecimal warehouseFee = BigDecimal.valueOf(warehouseFeeRate);
            BigDecimal warehouseBonus = goods.multiply(BigDecimal.valueOf(warehouseBonusRate)).setScale(2, BigDecimal.ROUND_HALF_UP);
            BigDecimal brand = goods.subtract(platform).subtract(warehouseFee);
            item.put("orderAmount", total);
            item.put("brandAmount", brand.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : brand);
            item.put("warehouseFee", warehouseFee);
            item.put("warehouseBonus", warehouseBonus);
            item.put("platformCommission", platform);
            item.put("shippingFee", shipping);
            item.put("settleTime", o.getFinishedAt() != null ? o.getFinishedAt() : o.getCreateTime());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    // ==================== 运营账号管理 ====================

    @GetMapping("/accounts/list")
    @RequireRole(9)
    public R<Map<String, Object>> getOperatorAccounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        IPage<User> pageResult = userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, 9)
                        .eq(User::getDeleted, 0)
                        .orderByAsc(User::getId));

        List<Map<String, Object>> records = pageResult.getRecords().stream().map(u -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.getId());
            item.put("username", u.getUsername());
            item.put("phone", u.getPhone());
            item.put("status", u.getStatus());
            item.put("createTime", u.getCreateTime());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    @PostMapping("/accounts/create")
    @RequireRole(9)
    public R<String> createOperatorAccount(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        if (username == null || password == null) throw new BusinessException("用户名和密码不能为空");

        long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).eq(User::getDeleted, 0));
        if (exists > 0) throw new BusinessException("用户名已存在");

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setPhone((String) body.getOrDefault("phone", ""));
        user.setRole(9);
        user.setStatus(1);
        user.setDeleted(0);
        userMapper.insert(user);
        return R.ok("创建成功");
    }

    @PutMapping("/accounts/{id}/status")
    @RequireRole(9)
    public R<String> updateAccountStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("账号不存在");
        int status = ((Number) body.get("status")).intValue();
        user.setStatus(status);
        userMapper.updateById(user);
        return R.ok("操作成功");
    }

    @PostMapping("/accounts/reset-password")
    @RequireRole(9)
    public R<String> resetPassword(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("id").toString());
        String newPassword = (String) body.get("password");
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("账号不存在");
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        return R.ok("密码重置成功");
    }

    @DeleteMapping("/accounts/{id}")
    @RequireRole(9)
    public R<String> deleteAccount(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("账号不存在");
        user.setDeleted(1);
        userMapper.updateById(user);
        return R.ok("删除成功");
    }

    // ==================== 售后退款管理 ====================

    /**
     * 售后申请列表（分页）
     * GET /api/operator/refund/list?page=1&size=10&refundStatus=REQUESTED&keyword=
     */
    @GetMapping("/refund/list")
    @RequireRole(9)
    public R<Map<String, Object>> getRefundList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String refundStatus,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getDeleted, 0)
                .isNotNull(Order::getRefundStatus)
                .orderByDesc(Order::getRefundRequestedAt);

        if (refundStatus != null && !refundStatus.isBlank()) {
            wrapper.eq(Order::getRefundStatus, refundStatus);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Order::getOrderSn, keyword);
        }

        IPage<Order> orderPage = orderMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Order o : orderPage.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", o.getId());
            item.put("orderSn", o.getOrderSn());
            item.put("totalAmount", o.getTotalAmount());
            item.put("status", o.getStatus());
            item.put("refundStatus", o.getRefundStatus());
            item.put("refundType", o.getRefundType());
            item.put("refundReason", o.getRefundReason());
            item.put("refundRemark", o.getRefundRemark());
            item.put("refundRequestedAt", o.getRefundRequestedAt());
            item.put("refundHandledAt", o.getRefundHandledAt());
            // 消费者信息
            User u = userMapper.selectById(o.getUserId());
            item.put("userId", o.getUserId());
            item.put("username", u != null ? u.getUsername() : "");
            item.put("nickname", u != null ? u.getUsername() : "");
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", orderPage.getTotal());
        result.put("page", page);
        result.put("size", size);

        // 待处理数量
        long pendingCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getDeleted, 0)
                .eq(Order::getRefundStatus, "REQUESTED"));
        result.put("pendingCount", pendingCount);

        return R.ok(result);
    }

    /**
     * 处理售后申请（批准/拒绝）
     * PUT /api/operator/refund/{orderId}/handle
     * Body: { "action": "approve" | "reject", "remark": "..." }
     *
     * approve → 退款到消费者钱包 + 订单状态变 CANCELLED + refundStatus=REFUNDED
     * reject  → refundStatus=REJECTED + refundRemark=拒绝原因
     */
    @PutMapping("/refund/{orderId}/handle")
    @RequireRole(9)
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> handleRefund(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> body) {

        String action = body.getOrDefault("action", "");
        String remark = body.getOrDefault("remark", "");

        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getDeleted() == 1) {
            throw new BusinessException("订单不存在");
        }
        if (!"REQUESTED".equals(order.getRefundStatus())) {
            throw new BusinessException("该申请当前状态无法处理（已处理过或未申请）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("orderSn", order.getOrderSn());

        if ("approve".equals(action)) {
            // ── 批准：退款到消费者钱包 ──
            Long userId = order.getUserId();
            BigDecimal refundAmount = order.getTotalAmount() != null
                    ? order.getTotalAmount() : BigDecimal.ZERO;

            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                // 确保钱包存在
                Wallet wallet = walletMapper.selectOne(new LambdaQueryWrapper<Wallet>()
                        .eq(Wallet::getUserId, userId));
                if (wallet == null) {
                    wallet = new Wallet();
                    wallet.setUserId(userId);
                    wallet.setBalance(BigDecimal.ZERO);
                    wallet.setFrozen(BigDecimal.ZERO);
                    walletMapper.insert(wallet);
                }
                walletMapper.updateBalance(userId, refundAmount);
                BigDecimal balanceAfter = walletMapper.selectOne(
                        new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId)).getBalance();
                // 记录流水
                WalletController walletController = applicationContext.getBean(WalletController.class);
                walletController.recordTransaction(userId, "REFUND", refundAmount,
                        balanceAfter, "售后退款 " + order.getOrderSn(), orderId);
                result.put("refundAmount", refundAmount);
                result.put("balanceAfter", balanceAfter);
                log.info("售后退款 orderId={} 金额¥{} 已退至用户{}钱包", orderId, refundAmount, userId);
            }

            order.setRefundStatus("REFUNDED");
            order.setRefundHandledAt(LocalDateTime.now());
            order.setRefundRemark(remark);
            order.setStatus("CANCELLED"); // 订单归入已取消
            orderMapper.updateById(order);

            result.put("action", "approve");
            result.put("refundStatus", "REFUNDED");
            return R.ok("退款已批准，款项已退至消费者钱包", result);

        } else if ("reject".equals(action)) {
            // ── 拒绝 ──
            if (remark.isBlank()) {
                throw new BusinessException("拒绝时必须填写原因");
            }
            order.setRefundStatus("REJECTED");
            order.setRefundHandledAt(LocalDateTime.now());
            order.setRefundRemark(remark);
            orderMapper.updateById(order);

            result.put("action", "reject");
            result.put("refundStatus", "REJECTED");
            return R.ok("已拒绝退款申请", result);

        } else {
            throw new BusinessException("无效操作，action 只能为 approve 或 reject");
        }
    }

    // ==================== 商品列表（供活动配置使用） ====================

    /**
     * 商品列表（运营端选品用）
     * GET /api/operator/product/list?page=1&size=100&keyword=
     */
    @GetMapping("/product/list")
    @RequireRole(9)
    public R<Map<String, Object>> getProductListForOperator(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
            .eq(Product::getDeleted, 0)
            .orderByDesc(Product::getCreateTime);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Product::getName, keyword);
        }

        IPage<Product> productPage = productMapper.selectPage(new Page<>(page, size), wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", productPage.getRecords());
        result.put("total", productPage.getTotal());
        result.put("page", productPage.getCurrent());
        result.put("size", productPage.getSize());
        return R.ok(result);
    }

    // ==================== 拼团拉新活动管理 ====================

    /**
     * 活动列表（分页）
     * GET /api/operator/activity/list
     */
    @GetMapping("/activity/list")
    @RequireRole(9)
    public R<Map<String, Object>> getActivityList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        IPage<Activity> actPage = activityMapper.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<Activity>()
                .eq(Activity::getDeleted, 0)
                .orderByDesc(Activity::getCreateTime));

        List<Map<String, Object>> records = new ArrayList<>();
        for (Activity a : actPage.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("name", a.getName());
            item.put("activityPrice", a.getActivityPrice());
            item.put("originalPrice", a.getOriginalPrice());
            item.put("startTime", a.getStartTime());
            item.put("endTime", a.getEndTime());
            item.put("maxInvitePerUser", a.getMaxInvitePerUser());
            item.put("totalQuota", a.getTotalQuota());
            item.put("usedQuota", a.getUsedQuota());
            item.put("status", a.getStatus());
            item.put("description", a.getDescription());
            item.put("subsidyPerOrder", a.getSubsidyPerOrder());
            Product p = productMapper.selectById(a.getProductId());
            item.put("productId", a.getProductId());
            item.put("productName", p != null ? p.getName() : "商品已删除");
            item.put("productImage", p != null ? p.getImages() : null);
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", actPage.getTotal());
        result.put("page", page);
        result.put("size", size);
        return R.ok(result);
    }

    /**
     * 创建活动
     * POST /api/operator/activity/create
     */
    @PostMapping("/activity/create")
    @RequireRole(9)
    public R<Map<String, Object>> createActivity(@RequestBody Map<String, Object> body) {
        Activity activity = new Activity();
        activity.setName((String) body.get("name"));
        activity.setProductId(((Number) body.get("productId")).longValue());
        activity.setActivityPrice(new BigDecimal(body.get("activityPrice").toString()));
        activity.setMaxInvitePerUser(body.get("maxInvitePerUser") != null
            ? ((Number) body.get("maxInvitePerUser")).intValue() : 3);
        activity.setTotalQuota(body.get("totalQuota") != null
            ? ((Number) body.get("totalQuota")).intValue() : 0);
        activity.setUsedQuota(0);
        activity.setStatus(1);
        activity.setDescription((String) body.getOrDefault("description", ""));
        activity.setDeleted(0);

        // 原价快照
        Product p = productMapper.selectById(activity.getProductId());
        if (p == null) throw new BusinessException("商品不存在");
        activity.setOriginalPrice(p.getRetailPrice());
        activity.setSubsidyPerOrder(p.getRetailPrice().subtract(activity.getActivityPrice()));

        // 时间
        String startStr = (String) body.get("startTime");
        String endStr = (String) body.get("endTime");
        try {
            activity.setStartTime(LocalDateTime.parse(startStr.replace(" ", "T")));
            activity.setEndTime(LocalDateTime.parse(endStr.replace(" ", "T")));
        } catch (Exception e) {
            throw new BusinessException("时间格式错误（需 yyyy-MM-dd HH:mm:ss）");
        }

        activityMapper.insert(activity);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", activity.getId());
        return R.ok("活动创建成功", res);
    }

    /**
     * 更新活动状态（启用/关闭）
     * PUT /api/operator/activity/{id}/status
     * Body: { "status": 1 }
     */
    @PutMapping("/activity/{id}/status")
    @RequireRole(9)
    public R<String> updateActivityStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Activity a = activityMapper.selectById(id);
        if (a == null) throw new BusinessException("活动不存在");
        a.setStatus(((Number) body.get("status")).intValue());
        activityMapper.updateById(a);
        return R.ok("更新成功");
    }

    /**
     * 删除活动
     * DELETE /api/operator/activity/{id}
     */
    @DeleteMapping("/activity/{id}")
    @RequireRole(9)
    public R<String> deleteActivity(@PathVariable Long id) {
        Activity a = activityMapper.selectById(id);
        if (a == null) throw new BusinessException("活动不存在");
        a.setDeleted(1);
        activityMapper.updateById(a);
        return R.ok("删除成功");
    }

    /**
     * 拉新数据看板
     * GET /api/operator/activity/dashboard
     */
    @GetMapping("/activity/dashboard")
    @RequireRole(9)
    public R<Map<String, Object>> getActivityDashboard() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 全部邀请总数
        long totalInvites = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>());
        data.put("totalInvites", totalInvites);

        // 成功拉新数（被邀请人完成注册以上）
        long successInvites = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>()
                .in(ActivityInvite::getStatus,
                    Arrays.asList("REGISTERED", "ORDERED", "PICKED_UP")));
        data.put("successInvites", successInvites);

        // 已下单数
        long orderedCount = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>()
                .in(ActivityInvite::getStatus, Arrays.asList("ORDERED", "PICKED_UP")));
        data.put("orderedCount", orderedCount);

        // 已核销数
        long pickedUpCount = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getStatus, "PICKED_UP"));
        data.put("pickedUpCount", pickedUpCount);

        // 核销率
        String pickupRate = orderedCount > 0
            ? String.format("%.1f%%", pickedUpCount * 100.0 / orderedCount) : "0%";
        data.put("pickupRate", pickupRate);

        // 今日拉新
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayNew = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>()
                .ge(ActivityInvite::getCreateTime, todayStart)
                .ne(ActivityInvite::getStatus, "PENDING"));
        data.put("todayNewUsers", todayNew);

        // 总补贴成本
        List<Activity> allActivities = activityMapper.selectList(
            new LambdaQueryWrapper<Activity>().eq(Activity::getDeleted, 0));
        BigDecimal totalSubsidy = BigDecimal.ZERO;
        for (Activity a : allActivities) {
            if (a.getSubsidyPerOrder() != null && a.getUsedQuota() > 0) {
                totalSubsidy = totalSubsidy.add(
                    a.getSubsidyPerOrder().multiply(BigDecimal.valueOf(a.getUsedQuota())));
            }
        }
        data.put("totalSubsidy", totalSubsidy);

        // 活动列表带统计
        List<Map<String, Object>> actStats = new ArrayList<>();
        for (Activity a : allActivities) {
            // 邀请总数（所有状态）
            long invites = activityInviteMapper.selectCount(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getActivityId, a.getId()));
            // 新增注册用户数：inviteeId 不为空，即被邀请人已完成注册（含后续下单/核销）
            long registeredCount = activityInviteMapper.selectCount(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getActivityId, a.getId())
                    .isNotNull(ActivityInvite::getInviteeId));
            // 已下单数
            long aOrdered = activityInviteMapper.selectCount(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getActivityId, a.getId())
                    .in(ActivityInvite::getStatus, Arrays.asList("ORDERED", "PICKED_UP")));
            // 已核销数
            long aPickedUp = activityInviteMapper.selectCount(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getActivityId, a.getId())
                    .eq(ActivityInvite::getStatus, "PICKED_UP"));
            // 今日新增（inviteeId 非空且今日绑定）
            LocalDateTime aTodayStart = java.time.LocalDate.now().atStartOfDay();
            long aTodayNew = activityInviteMapper.selectCount(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getActivityId, a.getId())
                    .isNotNull(ActivityInvite::getInviteeId)
                    .ge(ActivityInvite::getUpdateTime, aTodayStart));
            // 补贴成本
            java.math.BigDecimal aSubsidy = java.math.BigDecimal.ZERO;
            if (a.getSubsidyPerOrder() != null && aOrdered > 0) {
                aSubsidy = a.getSubsidyPerOrder().multiply(java.math.BigDecimal.valueOf(aOrdered));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("activityId", a.getId());
            row.put("name", a.getName());
            row.put("status", a.getStatus());
            row.put("invites", invites);
            row.put("successInvites", registeredCount);   // 实际注册新用户数
            row.put("orderedCount", aOrdered);
            row.put("pickedUp", aPickedUp);
            row.put("todayNew", aTodayNew);
            row.put("subsidyCost", aSubsidy);
            Product p = productMapper.selectById(a.getProductId());
            row.put("productName", p != null ? p.getName() : "");
            row.put("activityPrice", a.getActivityPrice());
            actStats.add(row);
        }
        data.put("activityStats", actStats);

        return R.ok(data);
    }

    // ==================== 平台钱包 ====================

    /**
     * 查询平台钱包余额
     * GET /api/operator/finance/wallet
     */
    @GetMapping("/finance/wallet")
    @RequireRole(9)
    public R<Map<String, Object>> getPlatformWallet() {
        // 查询 role=9 的平台账号（取第一个）
        User platformUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, 9)
                        .eq(User::getDeleted, 0)
                        .orderByAsc(User::getId)
                        .last("LIMIT 1"));
        if (platformUser == null) {
            return R.fail("平台账号不存在");
        }
        Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, platformUser.getId()));
        BigDecimal balance = wallet != null ? wallet.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen  = wallet != null ? wallet.getFrozen()  : BigDecimal.ZERO;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", platformUser.getId());
        result.put("username", platformUser.getUsername());
        result.put("balance", balance);
        result.put("frozen", frozen);
        result.put("available", balance.subtract(frozen));
        return R.ok(result);
    }

    /**
     * 查询平台收益流水（分页）
     * GET /api/operator/finance/transactions?page=1&size=20
     */
    @GetMapping("/finance/transactions")
    @RequireRole(9)
    public R<Map<String, Object>> getPlatformTransactions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        User platformUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, 9)
                        .eq(User::getDeleted, 0)
                        .orderByAsc(User::getId)
                        .last("LIMIT 1"));
        if (platformUser == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total", 0);
            empty.put("list", Collections.emptyList());
            return R.ok(empty);
        }

        Page<WalletTransaction> pageParam = new Page<>(page, size);
        Page<WalletTransaction> result = walletTransactionMapper.selectPage(
                pageParam,
                new LambdaQueryWrapper<WalletTransaction>()
                        .eq(WalletTransaction::getUserId, platformUser.getId())
                        .orderByDesc(WalletTransaction::getCreateTime));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("list", result.getRecords());
        return R.ok(data);
    }

    // ==================== 外单管理 ====================

    /**
     * 外单统计概览
     * GET /api/operator/external/stats
     */
    @GetMapping("/external/stats")
    @RequireRole(9)
    public R<Map<String, Object>> getExternalStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 各状态数量
        long total     = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0));
        long locked    = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 1));
        long picking   = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 2));
        long shipped   = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 3));
        long exception = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 4));
        long cancelled = externalOrderMapper.selectCount(new LambdaQueryWrapper<ExternalOrder>().eq(ExternalOrder::getDeleted, 0).eq(ExternalOrder::getStatus, 5));
        data.put("total", total);
        data.put("locked", locked);
        data.put("picking", picking);
        data.put("shipped", shipped);
        data.put("exception", exception);
        data.put("cancelled", cancelled);

        // 批次总数
        long batches = externalBatchMapper.selectCount(new LambdaQueryWrapper<ExternalBatch>().eq(ExternalBatch::getDeleted, 0));
        data.put("batches", batches);

        // 外单服务费收入汇总（来自 wallet_transaction type=INCOME remark含"外单服务费"）
        List<WalletTransaction> extTxList = walletTransactionMapper.selectList(
                new LambdaQueryWrapper<WalletTransaction>()
                        .eq(WalletTransaction::getType, "INCOME")
                        .like(WalletTransaction::getRemark, "外单服务费"));
        BigDecimal totalServiceFee = extTxList.stream()
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.put("totalServiceFee", totalServiceFee);

        return R.ok(data);
    }

    /**
     * 外单列表（分页）
     * GET /api/operator/external/list?page=1&size=20&status=&keyword=&brandId=&warehouseId=
     */
    @GetMapping("/external/list")
    @RequireRole(9)
    public R<Map<String, Object>> getExternalOrderList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long warehouseId) {

        LambdaQueryWrapper<ExternalOrder> wrapper = new LambdaQueryWrapper<ExternalOrder>()
                .eq(ExternalOrder::getDeleted, 0)
                .orderByDesc(ExternalOrder::getCreateTime);
        if (status != null) wrapper.eq(ExternalOrder::getStatus, status);
        if (brandId != null) wrapper.eq(ExternalOrder::getBrandId, brandId);
        if (warehouseId != null) wrapper.eq(ExternalOrder::getWarehouseId, warehouseId);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(ExternalOrder::getExternalOrderNo, keyword)
                    .or().like(ExternalOrder::getReceiverName, keyword)
                    .or().like(ExternalOrder::getSkuCode, keyword));
        }

        IPage<ExternalOrder> pageResult = externalOrderMapper.selectPage(new Page<>(page, size), wrapper);

        // 组装返回数据（补充品牌名、仓库名）
        List<Map<String, Object>> records = pageResult.getRecords().stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.getId());
            item.put("externalOrderNo", o.getExternalOrderNo());
            item.put("batchId", o.getBatchId());
            item.put("brandId", o.getBrandId());
            item.put("warehouseId", o.getWarehouseId());
            item.put("skuCode", o.getSkuCode());
            item.put("productName", o.getProductName());
            item.put("channel", o.getChannel());
            item.put("quantity", o.getQuantity());
            item.put("receiverName", o.getReceiverName());
            item.put("receiverPhone", o.getReceiverPhone());
            item.put("receiverAddress", o.getReceiverAddress());
            item.put("status", o.getStatus());
            String statusLabel;
            switch (o.getStatus() != null ? o.getStatus() : -1) {
                case 0: statusLabel = "待处理"; break;
                case 1: statusLabel = "库存锁定"; break;
                case 2: statusLabel = "拣货中"; break;
                case 3: statusLabel = "已发货"; break;
                case 4: statusLabel = "异常"; break;
                case 5: statusLabel = "已取消"; break;
                default: statusLabel = "未知";
            }
            item.put("statusLabel", statusLabel);
            item.put("logisticsCompany", o.getLogisticsCompany());
            item.put("logisticsNo", o.getLogisticsNo());
            item.put("shipTime", o.getShipTime());
            item.put("exceptionReason", o.getExceptionReason());
            item.put("failReason", o.getFailReason());
            item.put("createTime", o.getCreateTime());
            item.put("updateTime", o.getUpdateTime());
            // 补充仓库名
            if (o.getWarehouseId() != null) {
                Warehouse wh = warehouseMapper.selectById(o.getWarehouseId());
                item.put("warehouseName", wh != null ? wh.getName() : "--");
            } else {
                item.put("warehouseName", "--");
            }
            // 补充品牌名
            if (o.getBrandId() != null) {
                Brand brand = brandMapper.selectById(o.getBrandId());
                item.put("brandName", brand != null ? brand.getCompanyName() : "--");
            } else {
                item.put("brandName", "--");
            }
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    /**
     * 外单分账流水（外单服务费收益明细）
     * GET /api/operator/external/settlement?page=1&size=20
     */
    @GetMapping("/external/settlement")
    @RequireRole(9)
    public R<Map<String, Object>> getExternalSettlement(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<WalletTransaction> wrapper = new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getType, "INCOME")
                .like(WalletTransaction::getRemark, "外单服务费")
                .orderByDesc(WalletTransaction::getCreateTime);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(WalletTransaction::getRemark, keyword));
        }

        IPage<WalletTransaction> pageResult = walletTransactionMapper.selectPage(new Page<>(page, size), wrapper);

        // 补充仓库用户名
        List<Map<String, Object>> records = pageResult.getRecords().stream().map(tx -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tx.getId());
            item.put("userId", tx.getUserId());
            item.put("amount", tx.getAmount());
            item.put("balanceAfter", tx.getBalanceAfter());
            item.put("remark", tx.getRemark());
            item.put("refId", tx.getRefId());
            item.put("createTime", tx.getCreateTime());
            // 查仓库用户名
            if (tx.getUserId() != null) {
                User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getId, tx.getUserId()).eq(User::getDeleted, 0));
                item.put("warehouseUsername", u != null ? u.getUsername() : "--");
                // 查对应仓库名
                Warehouse wh = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getUserId, tx.getUserId()).eq(Warehouse::getDeleted, 0).last("LIMIT 1"));
                item.put("warehouseName", wh != null ? wh.getName() : "--");
            } else {
                item.put("warehouseUsername", "--");
                item.put("warehouseName", "--");
            }
            return item;
        }).collect(Collectors.toList());

        // 汇总总服务费
        List<WalletTransaction> allList = walletTransactionMapper.selectList(
                new LambdaQueryWrapper<WalletTransaction>()
                        .eq(WalletTransaction::getType, "INCOME")
                        .like(WalletTransaction::getRemark, "外单服务费"));
        BigDecimal totalFee = allList.stream()
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        result.put("totalServiceFee", totalFee);
        return R.ok(result);
    }
}

