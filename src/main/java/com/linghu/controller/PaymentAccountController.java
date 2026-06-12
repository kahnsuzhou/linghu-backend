package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.UserPaymentAccount;
import com.linghu.mapper.UserPaymentAccountMapper;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 支付账号绑定/查询/解绑（三端通用）
 *
 * GET  /api/payment/account/list          查询已绑定列表
 * POST /api/payment/account/bind          绑定新账号
 * POST /api/payment/account/set-default/{id}  设为默认
 * DELETE /api/payment/account/{id}        解绑
 */
@Slf4j
@RestController
@RequestMapping("/api/payment/account")
@RequiredArgsConstructor
public class PaymentAccountController {

    private final UserPaymentAccountMapper accountMapper;

    /**
     * 查询已绑定的支付账号列表
     */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> listAccounts() {
        Long userId = SecurityUtil.getCurrentUserId();
        List<UserPaymentAccount> accounts = accountMapper.selectList(
                new LambdaQueryWrapper<UserPaymentAccount>()
                        .eq(UserPaymentAccount::getUserId, userId)
                        .eq(UserPaymentAccount::getDeleted, 0)
                        .orderByDesc(UserPaymentAccount::getIsDefault)
                        .orderByDesc(UserPaymentAccount::getCreateTime));

        List<Map<String, Object>> result = accounts.stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("channel", a.getChannel());
            item.put("channelName", "ALIPAY".equals(a.getChannel()) ? "支付宝" : "微信");
            item.put("accountNo", maskAccountNo(a.getAccountNo()));
            item.put("realName", a.getRealName());
            item.put("isDefault", a.getIsDefault());
            item.put("createTime", a.getCreateTime());
            return item;
        }).collect(Collectors.toList());

        return R.ok(result);
    }

    /**
     * 绑定支付账号
     * body: { "channel": "ALIPAY", "accountNo": "13800138000", "realName": "张三" }
     */
    @PostMapping("/bind")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> bindAccount(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();

        String channel = (String) body.get("channel");
        String accountNo = (String) body.get("accountNo");
        String realName = (String) body.get("realName");

        if (channel == null || (!channel.equals("ALIPAY") && !channel.equals("WECHAT"))) {
            throw new BusinessException("渠道参数错误，支持 ALIPAY 或 WECHAT");
        }
        if (accountNo == null || accountNo.trim().isEmpty()) {
            throw new BusinessException("账号不能为空");
        }

        // 检查是否已绑定同渠道账号
        long existCount = accountMapper.selectCount(
                new LambdaQueryWrapper<UserPaymentAccount>()
                        .eq(UserPaymentAccount::getUserId, userId)
                        .eq(UserPaymentAccount::getChannel, channel)
                        .eq(UserPaymentAccount::getDeleted, 0));

        if (existCount > 0) {
            // 已存在同渠道，直接更新
            accountMapper.update(null, new LambdaUpdateWrapper<UserPaymentAccount>()
                    .eq(UserPaymentAccount::getUserId, userId)
                    .eq(UserPaymentAccount::getChannel, channel)
                    .eq(UserPaymentAccount::getDeleted, 0)
                    .set(UserPaymentAccount::getAccountNo, accountNo.trim())
                    .set(UserPaymentAccount::getRealName, realName));
            log.info("用户[{}] 更新{}绑定账号: {}", userId, channel, maskAccountNo(accountNo));
        } else {
            // 首次绑定：检查是否已有其他渠道的账号，没有则设为默认
            long totalCount = accountMapper.selectCount(
                    new LambdaQueryWrapper<UserPaymentAccount>()
                            .eq(UserPaymentAccount::getUserId, userId)
                            .eq(UserPaymentAccount::getDeleted, 0));

            UserPaymentAccount account = new UserPaymentAccount();
            account.setUserId(userId);
            account.setChannel(channel);
            account.setAccountNo(accountNo.trim());
            account.setRealName(realName);
            account.setIsDefault(totalCount == 0 ? 1 : 0); // 首个账号自动设为默认
            account.setDeleted(0);
            accountMapper.insert(account);
            log.info("用户[{}] 绑定{}账号: {}", userId, channel, maskAccountNo(accountNo));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("channel", channel);
        result.put("channelName", "ALIPAY".equals(channel) ? "支付宝" : "微信");
        result.put("accountNo", maskAccountNo(accountNo));
        return R.ok("绑定成功", result);
    }

    /**
     * 设为默认收款账号
     */
    @PostMapping("/set-default/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> setDefault(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();

        UserPaymentAccount account = accountMapper.selectById(id);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException("账号不存在");
        }

        // 先清空所有默认标记
        accountMapper.update(null, new LambdaUpdateWrapper<UserPaymentAccount>()
                .eq(UserPaymentAccount::getUserId, userId)
                .eq(UserPaymentAccount::getDeleted, 0)
                .set(UserPaymentAccount::getIsDefault, 0));

        // 设置新默认
        accountMapper.update(null, new LambdaUpdateWrapper<UserPaymentAccount>()
                .eq(UserPaymentAccount::getId, id)
                .set(UserPaymentAccount::getIsDefault, 1));

        return R.ok("已设为默认账号", null);
    }

    /**
     * 解绑账号（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> unbindAccount(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();

        UserPaymentAccount account = accountMapper.selectById(id);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException("账号不存在");
        }

        accountMapper.update(null, new LambdaUpdateWrapper<UserPaymentAccount>()
                .eq(UserPaymentAccount::getId, id)
                .set(UserPaymentAccount::getDeleted, 1));

        log.info("用户[{}] 解绑{}账号: {}", userId, account.getChannel(), maskAccountNo(account.getAccountNo()));
        return R.ok("解绑成功", null);
    }

    /**
     * 账号脱敏：手机号中间4位替换为****，其他格式保留首尾各2字符
     */
    private String maskAccountNo(String accountNo) {
        if (accountNo == null || accountNo.length() < 4) return accountNo;
        // 手机号格式（11位数字）
        if (accountNo.matches("^1[3-9]\\d{9}$")) {
            return accountNo.substring(0, 3) + "****" + accountNo.substring(7);
        }
        // 邮箱格式
        if (accountNo.contains("@")) {
            int atIndex = accountNo.indexOf('@');
            if (atIndex > 2) {
                return accountNo.substring(0, 2) + "****" + accountNo.substring(atIndex);
            }
            return accountNo.substring(0, 1) + "****" + accountNo.substring(atIndex);
        }
        // 其他：保留首2末2
        int len = accountNo.length();
        return accountNo.substring(0, 2) + "****" + accountNo.substring(len - 2);
    }
}
