package com.linghu.controller;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayFundTransUniTransferRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayFundTransUniTransferResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.PaymentOrder;
import com.linghu.entity.UserPaymentAccount;
import com.linghu.mapper.PaymentOrderMapper;
import com.linghu.mapper.UserPaymentAccountMapper;
import com.linghu.mapper.WalletMapper;
import com.linghu.service.PaymentFMService;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 支付控制器：充值（H5）+ 提现（转账）+ 异步回调
 *
 * 支付宝/微信 SDK 未配置时（enabled=false），充值/提现自动退回模拟模式。
 *
 * POST /api/payment/recharge           发起充值，返回 H5 支付链接
 * POST /api/payment/withdraw           发起提现
 * POST /api/payment/alipay/notify      支付宝异步回调（公开）
 * POST /api/payment/wechat/notify      微信异步回调（公开）
 * GET  /api/payment/order/{orderNo}    查询支付单状态
 */
@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentOrderMapper paymentOrderMapper;
    private final UserPaymentAccountMapper accountMapper;
    private final WalletMapper walletMapper;
    private final WalletController walletController;
    private final ObjectMapper objectMapper;
    @Autowired
    private PaymentFMService paymentFMService;

    @Autowired(required = false)
    private AlipayClient alipayClient;

    // 支付宝配置
    @Value("${alipay.enabled:false}")
    private boolean alipayEnabled;

    @Value("${alipay.public-key:}")
    private String alipayPublicKey;

    @Value("${alipay.notify-url:}")
    private String alipayNotifyUrl;

    @Value("${alipay.return-url:}")
    private String alipayReturnUrl;

    // 微信支付配置
    @Value("${wechat.pay.enabled:false}")
    private boolean wechatEnabled;

    // ==================== 充值 ====================

    /**
     * 发起充值
     * body: { "amount": 100.00, "channel": "ALIPAY" }
     * 支付宝已配置时返回 H5 支付链接；否则直接模拟到账。
     */
    @PostMapping("/recharge")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> recharge(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        BigDecimal amount = parseBigDecimal(body.get("amount"));
        String channel = (String) body.get("channel");

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("充值金额必须大于0");
        }
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            throw new BusinessException("单次充值不超过10000元");
        }
        if (channel == null) channel = "ALIPAY";

        // ── 优先使用支付FM ──
        if (paymentFMService != null) {
            try {
                String orderNo = generateOrderNo("R");
                String subject = "灵狐钱包充值 ¥" + amount.toPlainString();
                System.out.println("=== 调用 createOrder，金额: " + amount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
                String payUrl = paymentFMService.createOrder(
                        orderNo,
                        amount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString(),
                        subject,
                        "https://www.aifox.club/api/payment/fm/notify"
                );
                PaymentOrder po = new PaymentOrder();
                po.setUserId(userId);
                po.setOrderNo(orderNo);
                po.setChannel("PAYMENT_FM");
                po.setType("RECHARGE");
                po.setAmount(amount);
                po.setStatus("PENDING");
                po.setPayUrl(payUrl);
                paymentOrderMapper.insert(po);
                Map<String, Object> result = new HashMap<>();
                result.put("orderNo", orderNo);
                result.put("payUrl", payUrl);
                result.put("channel", "PAYMENT_FM");
                result.put("mode", "H5");
                return R.ok("支付链接已生成，请跳转完成支付", result);
            } catch (Exception e) {
                log.error("支付FM创建订单失败，回退到默认支付", e);
            }
        }

        // ── 支付宝真实充值 ──
        if ("ALIPAY".equals(channel) && alipayEnabled && alipayClient != null) {
            return doAlipayRecharge(userId, amount, request);
        }

        // ── 微信真实充值（H5） ──
        if ("WECHAT".equals(channel) && wechatEnabled) {
            return doWechatRecharge(userId, amount, request);
        }

        // ── 模拟充值（直接到账） ──
        return doSimulateRecharge(userId, amount, channel);
    }

    private R<Map<String, Object>> doAlipayRecharge(Long userId, BigDecimal amount,
                                                     HttpServletRequest request) {
        String orderNo = generateOrderNo("R");
        String subject = "灵狐钱包充值 ¥" + amount.toPlainString();

        // 创建支付订单记录
        PaymentOrder po = new PaymentOrder();
        po.setUserId(userId);
        po.setOrderNo(orderNo);
        po.setChannel("ALIPAY");
        po.setType("RECHARGE");
        po.setAmount(amount);
        po.setStatus("PENDING");
        paymentOrderMapper.insert(po);

        try {
            AlipayTradeWapPayRequest payRequest = new AlipayTradeWapPayRequest();
            payRequest.setNotifyUrl(alipayNotifyUrl);
            payRequest.setReturnUrl(alipayReturnUrl + "?orderNo=" + orderNo);

            Map<String, Object> bizContent = new LinkedHashMap<>();
            bizContent.put("out_trade_no", orderNo);
            bizContent.put("total_amount", amount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            bizContent.put("subject", subject);
            bizContent.put("product_code", "QUICK_WAP_WAY");
            payRequest.setBizContent(objectMapper.writeValueAsString(bizContent));

            AlipayTradeWapPayResponse response = alipayClient.pageExecute(payRequest, "GET");
            if (response.isSuccess()) {
                String payUrl = response.getBody();
                // 更新 payUrl
                paymentOrderMapper.update(null, new LambdaUpdateWrapper<PaymentOrder>()
                        .eq(PaymentOrder::getOrderNo, orderNo)
                        .set(PaymentOrder::getPayUrl, payUrl));

                Map<String, Object> result = new HashMap<>();
                result.put("orderNo", orderNo);
                result.put("payUrl", payUrl);
                result.put("channel", "ALIPAY");
                result.put("mode", "H5");
                return R.ok("支付链接已生成，请跳转完成支付", result);
            } else {
                updateOrderStatus(orderNo, "FAILED", null);
                throw new BusinessException("支付宝下单失败：" + response.getSubMsg());
            }
        } catch (Exception e) {
            updateOrderStatus(orderNo, "FAILED", null);
            log.error("支付宝充值异常", e);
            throw new BusinessException("支付宝充值失败：" + e.getMessage());
        }
    }

    private R<Map<String, Object>> doWechatRecharge(Long userId, BigDecimal amount,
                                                     HttpServletRequest request) {
        // 微信 H5 支付需要真实配置，此处给出框架，详细实现与支付宝类似
        String orderNo = generateOrderNo("R");
        PaymentOrder po = new PaymentOrder();
        po.setUserId(userId);
        po.setOrderNo(orderNo);
        po.setChannel("WECHAT");
        po.setType("RECHARGE");
        po.setAmount(amount);
        po.setStatus("FAILED");
        paymentOrderMapper.insert(po);
        throw new BusinessException("微信 H5 支付配置尚未完成，请先配置 wechat.pay 参数");
    }

    private R<Map<String, Object>> doSimulateRecharge(Long userId, BigDecimal amount, String channel) {
        // 创建并标记成功的模拟支付单
        String orderNo = generateOrderNo("R");
        PaymentOrder po = new PaymentOrder();
        po.setUserId(userId);
        po.setOrderNo(orderNo);
        po.setChannel(channel);
        po.setType("RECHARGE");
        po.setAmount(amount);
        po.setStatus("SUCCESS");
        po.setChannelOrderNo("SIM" + System.currentTimeMillis());
        paymentOrderMapper.insert(po);

        // 直接更新钱包余额
        walletController.getOrCreateWallet(userId);
        walletMapper.updateBalance(userId, amount);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet>()
                        .eq(com.linghu.entity.Wallet::getUserId, userId);
        BigDecimal balanceAfter = walletMapper.selectOne(q).getBalance();
        walletController.recordTransaction(userId, "RECHARGE", amount, balanceAfter,
                "钱包充值（" + ("ALIPAY".equals(channel) ? "支付宝" : "微信") + "模拟）", null);

        log.info("模拟充值：用户[{}] ¥{} 渠道[{}]，余额：¥{}", userId, amount, channel, balanceAfter);

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", orderNo);
        result.put("amount", amount);
        result.put("balanceAfter", balanceAfter);
        result.put("mode", "SIMULATE");
        return R.ok("充值成功（模拟）", result);
    }

    // ==================== 提现 ====================

    /**
     * 发起提现
     * body: { "amount": 50.00, "accountId": 1 }
     * 若账号对应的渠道 SDK 已配置则调真实转账接口，否则模拟提现。
     */
    @PostMapping("/withdraw")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> withdraw(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        BigDecimal amount = parseBigDecimal(body.get("amount"));
        Long accountId = parseLong(body.get("accountId"));

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("提现金额必须大于0");
        }
        if (amount.compareTo(new BigDecimal("5000")) > 0) {
            throw new BusinessException("单次提现不超过5000元");
        }

        // 查询收款账号
        UserPaymentAccount account = null;
        if (accountId != null) {
            account = accountMapper.selectOne(new LambdaQueryWrapper<UserPaymentAccount>()
                    .eq(UserPaymentAccount::getId, accountId)
                    .eq(UserPaymentAccount::getUserId, userId)
                    .eq(UserPaymentAccount::getDeleted, 0));
        }
        if (account == null) {
            // 未指定 accountId，使用默认账号
            account = accountMapper.selectOne(new LambdaQueryWrapper<UserPaymentAccount>()
                    .eq(UserPaymentAccount::getUserId, userId)
                    .eq(UserPaymentAccount::getIsDefault, 1)
                    .eq(UserPaymentAccount::getDeleted, 0));
        }
        if (account == null) {
            throw new BusinessException("请先绑定支付宝或微信账号，再申请提现");
        }

        // 检查余额
        com.linghu.entity.Wallet wallet = walletController.getOrCreateWallet(userId);
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("余额不足，当前余额：¥" + wallet.getBalance());
        }

        String channel = account.getChannel();

        // 支付宝真实转账
        if ("ALIPAY".equals(channel) && alipayEnabled && alipayClient != null) {
            return doAlipayWithdraw(userId, amount, account);
        }

        // 微信提现（暂未实现，退回模拟）
        // if ("WECHAT".equals(channel) && wechatEnabled) { ... }

        // 模拟提现
        return doSimulateWithdraw(userId, amount, account);
    }

    private R<Map<String, Object>> doAlipayWithdraw(Long userId, BigDecimal amount,
                                                      UserPaymentAccount account) {
        String orderNo = generateOrderNo("W");

        // 先扣减余额（冻结效果）
        int updated = walletMapper.updateBalance(userId, amount.negate());
        if (updated == 0) throw new BusinessException("余额不足，提现失败");

        PaymentOrder po = new PaymentOrder();
        po.setUserId(userId);
        po.setOrderNo(orderNo);
        po.setChannel("ALIPAY");
        po.setType("WITHDRAW");
        po.setAmount(amount);
        po.setStatus("PENDING");
        paymentOrderMapper.insert(po);

        try {
            AlipayFundTransUniTransferRequest transferRequest = new AlipayFundTransUniTransferRequest();
            Map<String, Object> bizContent = new LinkedHashMap<>();
            bizContent.put("out_biz_no", orderNo);
            bizContent.put("trans_amount", amount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            bizContent.put("product_code", "TRANS_ACCOUNT_NO_PWD");
            bizContent.put("biz_scene", "DIRECT_TRANSFER");
            bizContent.put("order_title", "灵狐平台收益提现");

            Map<String, Object> payeeInfo = new LinkedHashMap<>();
            payeeInfo.put("identity", account.getAccountNo());
            payeeInfo.put("identity_type", "ALIPAY_LOGON_ID");
            if (account.getRealName() != null && !account.getRealName().isEmpty()) {
                payeeInfo.put("name", account.getRealName());
            }
            bizContent.put("payee_info", payeeInfo);
            transferRequest.setBizContent(objectMapper.writeValueAsString(bizContent));

            AlipayFundTransUniTransferResponse response = alipayClient.execute(transferRequest);
            if (response.isSuccess()) {
                String channelOrderNo = response.getOrderId();
                updateOrderStatus(orderNo, "SUCCESS", channelOrderNo);

                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet> q =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet>()
                                .eq(com.linghu.entity.Wallet::getUserId, userId);
                BigDecimal balanceAfter = walletMapper.selectOne(q).getBalance();
                walletController.recordTransaction(userId, "WITHDRAW", amount.negate(), balanceAfter,
                        "提现到支付宝 " + maskAccount(account.getAccountNo()), null);

                log.info("用户[{}] 支付宝提现 ¥{} 成功，流水号={}", userId, amount, channelOrderNo);

                Map<String, Object> result = new HashMap<>();
                result.put("orderNo", orderNo);
                result.put("amount", amount);
                result.put("balanceAfter", balanceAfter);
                result.put("channelOrderNo", channelOrderNo);
                return R.ok("提现成功，稍后到账支付宝", result);
            } else {
                // 转账失败：回滚余额
                walletMapper.updateBalance(userId, amount);
                updateOrderStatus(orderNo, "FAILED", null);
                throw new BusinessException("支付宝转账失败：" + response.getSubMsg());
            }
        } catch (AlipayApiException e) {
            walletMapper.updateBalance(userId, amount); // 回滚
            updateOrderStatus(orderNo, "FAILED", null);
            log.error("支付宝提现异常", e);
            throw new BusinessException("支付宝提现失败：" + e.getMessage());
        } catch (Exception e) {
            walletMapper.updateBalance(userId, amount); // 回滚
            updateOrderStatus(orderNo, "FAILED", null);
            log.error("提现异常", e);
            throw new BusinessException("提现失败，请稍后重试");
        }
    }

    private R<Map<String, Object>> doSimulateWithdraw(Long userId, BigDecimal amount,
                                                       UserPaymentAccount account) {
        String orderNo = generateOrderNo("W");

        int updated = walletMapper.updateBalance(userId, amount.negate());
        if (updated == 0) throw new BusinessException("余额不足，提现失败");

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet>()
                        .eq(com.linghu.entity.Wallet::getUserId, userId);
        BigDecimal balanceAfter = walletMapper.selectOne(q).getBalance();

        PaymentOrder po = new PaymentOrder();
        po.setUserId(userId);
        po.setOrderNo(orderNo);
        po.setChannel(account.getChannel());
        po.setType("WITHDRAW");
        po.setAmount(amount);
        po.setStatus("SUCCESS");
        po.setChannelOrderNo("SIM" + System.currentTimeMillis());
        paymentOrderMapper.insert(po);

        String channelName = "ALIPAY".equals(account.getChannel()) ? "支付宝" : "微信";
        walletController.recordTransaction(userId, "WITHDRAW", amount.negate(), balanceAfter,
                "提现到" + channelName + " " + maskAccount(account.getAccountNo()) + "（模拟）", null);

        log.info("模拟提现：用户[{}] ¥{} → {}[{}]，余额：¥{}", userId, amount,
                channelName, maskAccount(account.getAccountNo()), balanceAfter);

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", orderNo);
        result.put("amount", amount);
        result.put("balanceAfter", balanceAfter);
        result.put("toAccount", maskAccount(account.getAccountNo()));
        result.put("channel", account.getChannel());
        result.put("channelName", channelName);
        result.put("mode", "SIMULATE");
        result.put("message", "提现成功（模拟），预计1-3个工作日到账");
        return R.ok("提现申请已提交", result);
    }

    // ==================== 回调 ====================

    /**
     * 支付宝异步通知（公开，不需要登录）
     * POST /api/payment/alipay/notify
     */
    @PostMapping("/alipay/notify")
    public String alipayNotify(HttpServletRequest request) {
        try {
            // 获取支付宝通知参数
            Map<String, String> params = new HashMap<>();
            Map<String, String[]> requestParams = request.getParameterMap();
            for (String name : requestParams.keySet()) {
                params.put(name, String.join(",", requestParams.get(name)));
            }

            log.info("收到支付宝回调: trade_no={}, out_trade_no={}, trade_status={}",
                    params.get("trade_no"), params.get("out_trade_no"), params.get("trade_status"));

            // 验签
            boolean signVerified = AlipaySignature.rsaCheckV1(
                    params, alipayPublicKey, "UTF-8", "RSA2");

            if (!signVerified) {
                log.warn("支付宝回调验签失败");
                return "fail";
            }

            String tradeStatus = params.get("trade_status");
            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");

            // 只处理支付成功的通知
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                processRechargeSuccess(outTradeNo, tradeNo, params.toString());
            }

            return "success";
        } catch (Exception e) {
            log.error("处理支付宝回调异常", e);
            return "fail";
        }
    }

    /**
     * 微信支付异步通知（公开）
     * POST /api/payment/wechat/notify
     */
    @PostMapping("/wechat/notify")
    public Map<String, String> wechatNotify(HttpServletRequest request) {
        // 微信支付 V3 回调处理框架，需配置 WxPayService 后完善
        log.info("收到微信支付回调");
        Map<String, String> result = new HashMap<>();
        result.put("code", "SUCCESS");
        result.put("message", "成功");
        return result;
    }

    /**
     * 查询支付单状态
     * GET /api/payment/order/{orderNo}
     */
    @GetMapping("/order/{orderNo}")
    public R<Map<String, Object>> queryOrder(@PathVariable String orderNo) {
        Long userId = SecurityUtil.getCurrentUserId();

        PaymentOrder po = paymentOrderMapper.selectOne(
                new LambdaQueryWrapper<PaymentOrder>()
                        .eq(PaymentOrder::getOrderNo, orderNo)
                        .eq(PaymentOrder::getUserId, userId));

        if (po == null) throw new BusinessException("支付单不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", po.getOrderNo());
        result.put("channel", po.getChannel());
        result.put("type", po.getType());
        result.put("amount", po.getAmount());
        result.put("status", po.getStatus());
        result.put("channelOrderNo", po.getChannelOrderNo());
        result.put("createTime", po.getCreateTime());
        return R.ok(result);
    }

    // ==================== 内部工具 ====================

    @Transactional(rollbackFor = Exception.class)
    public void processRechargeSuccess(String orderNo, String channelOrderNo, String rawCallback) {
        PaymentOrder po = paymentOrderMapper.selectOne(
                new LambdaQueryWrapper<PaymentOrder>()
                        .eq(PaymentOrder::getOrderNo, orderNo));

        if (po == null) {
            log.warn("充值回调：找不到支付单 {}", orderNo);
            return;
        }
        if ("SUCCESS".equals(po.getStatus())) {
            log.info("充值回调：重复通知，已处理 {}", orderNo);
            return;
        }

        // 更新支付单
        paymentOrderMapper.update(null, new LambdaUpdateWrapper<PaymentOrder>()
                .eq(PaymentOrder::getOrderNo, orderNo)
                .set(PaymentOrder::getStatus, "SUCCESS")
                .set(PaymentOrder::getChannelOrderNo, channelOrderNo)
                .set(PaymentOrder::getCallbackRaw, rawCallback));

        // 增加余额
        Long userId = po.getUserId();
        BigDecimal amount = po.getAmount();
        walletController.getOrCreateWallet(userId);
        walletMapper.updateBalance(userId, amount);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linghu.entity.Wallet>()
                        .eq(com.linghu.entity.Wallet::getUserId, userId);
        BigDecimal balanceAfter = walletMapper.selectOne(q).getBalance();
        walletController.recordTransaction(userId, "RECHARGE", amount, balanceAfter,
                "支付宝充值到账", null);

        log.info("充值成功：用户[{}] ¥{}，余额：¥{}", userId, amount, balanceAfter);
    }

    private void updateOrderStatus(String orderNo, String status, String channelOrderNo) {
        LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<PaymentOrder>()
                .eq(PaymentOrder::getOrderNo, orderNo)
                .set(PaymentOrder::getStatus, status);
        if (channelOrderNo != null) {
            wrapper.set(PaymentOrder::getChannelOrderNo, channelOrderNo);
        }
        paymentOrderMapper.update(null, wrapper);
    }

    private String generateOrderNo(String prefix) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rand = (int) (Math.random() * 900000) + 100000;
        return prefix + date + rand;
    }

    private String maskAccount(String account) {
        if (account == null || account.length() < 4) return account;
        if (account.matches("^1[3-9]\\d{9}$")) {
            return account.substring(0, 3) + "****" + account.substring(7);
        }
        if (account.contains("@")) {
            int atIndex = account.indexOf('@');
            return account.substring(0, Math.min(2, atIndex)) + "****" + account.substring(atIndex);
        }
        int len = account.length();
        return account.substring(0, 2) + "****" + account.substring(len - 2);
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
