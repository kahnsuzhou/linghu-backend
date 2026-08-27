package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.service.WebSocketService;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 钱包控制器（三端通用）
 * 消费者：充值 + 余额支付订单
 * 仓主/品牌方：查收入 + 提现
 */
@Slf4j
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletMapper walletMapper;
    private final WalletTransactionMapper walletTransactionMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final WarehouseMapper warehouseMapper;
    private final WorkOrderMapper workOrderMapper;
    private final UserMapper userMapper;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    // ==================== 公共接口 ====================

    /**
     * 查询当前用户钱包信息
     * GET /api/wallet/info
     */
    @GetMapping("/info")
    public R<Map<String, Object>> getWalletInfo() {
        Long userId = SecurityUtil.getCurrentUserId();
        Wallet wallet = getOrCreateWallet(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("balance", wallet.getBalance());
        result.put("frozen", wallet.getFrozen());
        result.put("available", wallet.getBalance().subtract(wallet.getFrozen()));
        result.put("hasPayPassword", wallet.getPayPassword() != null && !wallet.getPayPassword().isBlank());
        return R.ok(result);
    }

    /**
     * 设置/修改支付密码
     * POST /api/wallet/set-pay-password
     * body: { "password": "123456" }  (6位数字)
     */
    @PostMapping("/set-pay-password")
    @RequireRole(0)
    public R<Void> setPayPassword(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        String password = (String) body.get("password");
        if (password == null || !password.matches("\\d{6}")) {
            throw new BusinessException("支付密码必须是6位数字");
        }
        Wallet wallet = getOrCreateWallet(userId);
        wallet.setPayPassword(passwordEncoder.encode(password));
        walletMapper.updateById(wallet);
        return R.ok("支付密码设置成功", null);
    }

    /**
     * 钱包余额支付（带支付密码校验）
     * POST /api/wallet/pay-with-password
     * body: { "orderId": 123, "password": "123456" }
     */
    @PostMapping("/pay-with-password")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> walletPayWithPassword(@RequestBody Map<String, Object> body) throws JsonProcessingException {
        Long userId = SecurityUtil.getCurrentUserId();
        Long orderId = parseLong(body.get("orderId"));
        String password = (String) body.get("password");
        if (orderId == null) throw new BusinessException("订单ID不能为空");
        if (password == null || password.isBlank()) throw new BusinessException("请输入支付密码");

        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) throw new BusinessException("订单不存在");
        if (!"PENDING_PAY".equals(order.getStatus())) throw new BusinessException("订单状态不正确");

        Wallet wallet = getOrCreateWallet(userId);

        // 校验支付密码
        if (wallet.getPayPassword() == null || wallet.getPayPassword().isBlank()) {
            throw new BusinessException("请先设置支付密码");
        }
        if (!passwordEncoder.matches(password, wallet.getPayPassword())) {
            throw new BusinessException("支付密码错误");
        }

        BigDecimal totalAmount = order.getTotalAmount();
        if (wallet.getBalance().compareTo(totalAmount) < 0) {
            throw new BusinessException(
                String.format("余额不足，订单金额：¥%.2f，当前余额：¥%.2f", totalAmount, wallet.getBalance()));
        }

        // 扣减余额
        int updated = walletMapper.updateBalance(userId, totalAmount.negate());
        if (updated == 0) throw new BusinessException("余额不足，支付失败");

        BigDecimal balanceAfter = walletMapper.selectOne(
            new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId)).getBalance();
        recordTransaction(userId, "PAY", totalAmount.negate(), balanceAfter, "支付订单 " + order.getOrderSn(), orderId);

        // 更新订单状态
        order.setStatus("PENDING_DELIVERY");
        order.setPaidAt(LocalDateTime.now());
        if ("pickup".equals(order.getDeliveryMode())) {
            String pickUpCode = String.format("%06d", (int)(Math.random() * 1000000));
            order.setPickUpCode(pickUpCode);
        }
        orderMapper.updateById(order);

        // 按仓库分组创建拣货工单
        List<OrderItem> items = orderItemMapper.selectList(
            new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        Map<Long, List<OrderItem>> warehouseItems = new HashMap<>();
        for (OrderItem item : items) {
            warehouseItems.computeIfAbsent(item.getWarehouseId(), k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<Long, List<OrderItem>> entry : warehouseItems.entrySet()) {
            Long warehouseId = entry.getKey();
            List<OrderItem> wItems = entry.getValue();
            List<Map<String, Object>> workItems = new ArrayList<>();
            for (OrderItem item : wItems) {
                Product product = productMapper.selectById(item.getProductId());
                Map<String, Object> wi = new HashMap<>();
                wi.put("productId", item.getProductId());
                wi.put("productName", product != null ? product.getName() : "未知商品");
                wi.put("barcode", product != null ? product.getBarcode() : "");
                wi.put("planQuantity", item.getQuantity());
                wi.put("scannedQuantity", 0);
                workItems.add(wi);
            }
            WorkOrder workOrder = new WorkOrder();
            workOrder.setType(2);
            workOrder.setWarehouseId(warehouseId);
            workOrder.setBrandId(wItems.get(0).getBrandId());
            workOrder.setOrderNo(order.getOrderSn());
            workOrder.setDeliveryMode(order.getDeliveryMode());
            workOrder.setStatus("PENDING");
            workOrder.setItems(objectMapper.writeValueAsString(workItems));
            workOrder.setDeleted(0);
            workOrderMapper.insert(workOrder);
            for (OrderItem item : wItems) {
                item.setWorkOrderId(workOrder.getId());
                orderItemMapper.updateById(item);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("balanceAfter", balanceAfter);
        log.info("钱包支付成功: userId={}, orderId={}, amount={}", userId, orderId, totalAmount);
        return R.ok("支付成功", result);
    }

    /**
     * 查询流水列表（分页）
     * GET /api/wallet/transactions?page=1&size=20
     */
    @GetMapping("/transactions")
    public R<Map<String, Object>> getTransactions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtil.getCurrentUserId();

        Page<WalletTransaction> pageParam = new Page<>(page, size);
        Page<WalletTransaction> result = walletTransactionMapper.selectPage(
                pageParam,
                new LambdaQueryWrapper<WalletTransaction>()
                        .eq(WalletTransaction::getUserId, userId)
                        .orderByDesc(WalletTransaction::getCreateTime));

        List<Map<String, Object>> items = result.getRecords().stream().map(tx -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", tx.getId());
            item.put("type", tx.getType());
            item.put("typeText", getTypeText(tx.getType()));
            item.put("amount", tx.getAmount());
            item.put("balanceAfter", tx.getBalanceAfter());
            item.put("remark", tx.getRemark());
            item.put("refId", tx.getRefId());
            item.put("createTime", tx.getCreateTime());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("total", result.getTotal());
        response.put("page", page);
        response.put("size", size);
        response.put("list", items);
        return R.ok(response);
    }

    /**
     * 提现（模拟：立即到账，扣减余额）
     * POST /api/wallet/withdraw  body: {"amount": 50.00}
     */
    @PostMapping("/withdraw")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> withdraw(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        BigDecimal amount = parseBigDecimal(body.get("amount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("提现金额必须大于0");
        }
        if (amount.compareTo(new BigDecimal("5000")) > 0) {
            throw new BusinessException("单次提现不超过5000元");
        }

        Wallet wallet = getOrCreateWallet(userId);
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("余额不足，当前余额：¥" + wallet.getBalance());
        }

        // 扣减余额
        int updated = walletMapper.updateBalance(userId, amount.negate());
        if (updated == 0) throw new BusinessException("余额不足，提现失败");

        // 查询最新余额
        BigDecimal balanceAfter = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId)).getBalance();

        // 写流水
        recordTransaction(userId, "WITHDRAW", amount.negate(), balanceAfter, "提现到银行卡（模拟）", null);

        log.info("用户[{}] 提现 ¥{}，余额剩余 ¥{}", userId, amount, balanceAfter);

        Map<String, Object> result = new HashMap<>();
        result.put("amount", amount);
        result.put("balanceAfter", balanceAfter);
        result.put("message", "提现成功，预计1-3个工作日到账（模拟）");
        return R.ok("提现申请已提交", result);
    }

    // ==================== 消费者专属 ====================

    /**
     * 充值（模拟：直接增加余额）
     * POST /api/wallet/recharge  body: {"amount": 100.00}
     */
    @PostMapping("/recharge")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> recharge(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        BigDecimal amount = parseBigDecimal(body.get("amount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("充值金额必须大于0");
        }
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            throw new BusinessException("单次充值不超过10000元");
        }

        getOrCreateWallet(userId); // 确保钱包存在
        walletMapper.updateBalance(userId, amount);
        BigDecimal balanceAfter = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId)).getBalance();

        recordTransaction(userId, "RECHARGE", amount, balanceAfter, "钱包充值（模拟）", null);

        log.info("用户[{}] 充值 ¥{}，余额：¥{}", userId, amount, balanceAfter);

        Map<String, Object> result = new HashMap<>();
        result.put("amount", amount);
        result.put("balanceAfter", balanceAfter);
        return R.ok("充值成功", result);
    }

    /**
     * 钱包支付订单
     * POST /api/wallet/pay  body: {"orderId": 123}
     */
    @PostMapping("/pay")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> walletPay(@RequestBody Map<String, Object> body) throws JsonProcessingException {
        Long userId = SecurityUtil.getCurrentUserId();
        Long orderId = parseLong(body.get("orderId"));
        if (orderId == null) throw new BusinessException("订单ID不能为空");

        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        if (!"PENDING_PAY".equals(order.getStatus())) {
            throw new BusinessException("订单状态不正确，当前：" + order.getStatus());
        }

        BigDecimal totalAmount = order.getTotalAmount();
        Wallet wallet = getOrCreateWallet(userId);
        if (wallet.getBalance().compareTo(totalAmount) < 0) {
            // 余额不足时，返回充值链接
            BigDecimal needAmount = totalAmount.subtract(wallet.getBalance());
            Map<String, Object> result = new HashMap<>();
            result.put("rechargeNeeded", true);
            result.put("needAmount", needAmount);
            result.put("orderId", orderId);
            result.put("message", "余额不足，需要充值 ¥" + needAmount.toPlainString() + " 完成支付");
            result.put("payUrl", "https://www.aifox.club/api/payment/recharge?amount=" + needAmount + "&orderId=" + orderId);
            return R.ok("请先充值完成支付", result);
        }

        // ① 扣减余额
        int updated = walletMapper.updateBalance(userId, totalAmount.negate());
        if (updated == 0) throw new BusinessException("余额不足，支付失败");

        BigDecimal balanceAfter = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId)).getBalance();
        recordTransaction(userId, "PAY", totalAmount.negate(), balanceAfter,
                "支付订单 " + order.getOrderSn(), orderId);

        // ② 更新订单状态
        order.setStatus("PENDING_DELIVERY");
        order.setPaidAt(LocalDateTime.now());
        orderMapper.updateById(order);

        // ③ 按仓库分组创建拣货工单（与 payCallback 逻辑相同）
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        Map<Long, List<OrderItem>> warehouseItems = new HashMap<>();
        for (OrderItem item : items) {
            warehouseItems.computeIfAbsent(item.getWarehouseId(), k -> new ArrayList<>()).add(item);
        }

        int workOrderCount = 0;
        for (Map.Entry<Long, List<OrderItem>> entry : warehouseItems.entrySet()) {
            Long warehouseId = entry.getKey();
            List<OrderItem> wItems = entry.getValue();

            List<Map<String, Object>> workItems = new ArrayList<>();
            for (OrderItem item : wItems) {
                Product product = productMapper.selectById(item.getProductId());
                Map<String, Object> wi = new HashMap<>();
                wi.put("productId", item.getProductId());
                wi.put("productName", product != null ? product.getName() : "未知商品");
                wi.put("barcode", product != null ? product.getBarcode() : "");
                wi.put("planQuantity", item.getQuantity());
                wi.put("scannedQuantity", 0);
                workItems.add(wi);
            }

            WorkOrder workOrder = new WorkOrder();
            workOrder.setType(2);
            workOrder.setWarehouseId(warehouseId);
            workOrder.setBrandId(wItems.get(0).getBrandId());
            workOrder.setOrderNo(order.getOrderSn());
            workOrder.setDeliveryMode(order.getDeliveryMode());
            workOrder.setStatus("PENDING");
            workOrder.setItems(objectMapper.writeValueAsString(workItems));
            workOrder.setDeleted(0);
            workOrderMapper.insert(workOrder);

            for (OrderItem item : wItems) {
                item.setWorkOrderId(workOrder.getId());
                orderItemMapper.updateById(item);
            }

            webSocketService.notifyWarehouse(warehouseId, "NEW_PICKING_ORDER", workOrder.getId());
            workOrderCount++;
        }

        log.info("用户[{}] 钱包支付订单[{}] ¥{}，余额剩余 ¥{}，创建拣货单 {} 个",
                userId, orderId, totalAmount, balanceAfter, workOrderCount);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("orderSn", order.getOrderSn());
        result.put("paidAmount", totalAmount);
        result.put("balanceAfter", balanceAfter);
        result.put("status", "PENDING_DELIVERY");
        result.put("workOrderCount", workOrderCount);
        return R.ok("支付成功，等待仓库发货", result);
    }

    // ==================== 工具方法 ====================

    /**
     * 获取或创建钱包（懒初始化）
     */
    public Wallet getOrCreateWallet(Long userId) {
        Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId));
        if (wallet == null) {
            wallet = new Wallet();
            wallet.setUserId(userId);
            wallet.setBalance(BigDecimal.ZERO);
            wallet.setFrozen(BigDecimal.ZERO);
            walletMapper.insert(wallet);
        }
        return wallet;
    }

    /**
     * 记录流水
     */
    public void recordTransaction(Long userId, String type, BigDecimal amount,
                                   BigDecimal balanceAfter, String remark, Long refId) {
        WalletTransaction tx = new WalletTransaction();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(balanceAfter);
        tx.setRemark(remark);
        tx.setRefId(refId);
        walletTransactionMapper.insert(tx);
    }

    private String getTypeText(String type) {
        switch (type) {
            case "RECHARGE": return "充值";
            case "PAY": return "消费支付";
            case "INCOME": return "收入到账";
            case "WITHDRAW": return "提现";
            case "REFUND": return "退款";
            default: return type;
        }
    }

    private BigDecimal parseBigDecimal(Object obj) {
        if (obj == null) return null;
        try {
            return new BigDecimal(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        try {
            return Long.valueOf(obj.toString().replace(".0", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
