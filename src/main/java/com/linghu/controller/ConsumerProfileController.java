package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.User;
import com.linghu.mapper.UserMapper;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 消费者个人中心控制器（会员开通 / VIP 查询）
 */
@Slf4j
@RestController
@RequestMapping("/api/consumer")
@RequiredArgsConstructor
public class ConsumerProfileController {

    private final UserMapper userMapper;

    // ==================== VIP 会员 ====================

    /**
     * 查询当前消费者会员状态
     * GET /api/consumer/vip/info
     */
    @GetMapping("/vip/info")
    @RequireRole(0)
    public R<Map<String, Object>> getVipInfo() {
        Long userId = SecurityUtil.getCurrentUserId();
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");

        boolean isVip = user.getVipLevel() != null && user.getVipLevel() > 0
                && user.getVipExpireTime() != null
                && user.getVipExpireTime().isAfter(LocalDateTime.now());

        Map<String, Object> result = new HashMap<>();
        result.put("vipLevel", user.getVipLevel() != null ? user.getVipLevel() : 0);
        result.put("vipExpireTime", user.getVipExpireTime());
        result.put("isVip", isVip);
        result.put("benefit", isVip ? "满30元免运费（快递¥6/外卖配送¥10）" : "开通会员享满30元免运费");
        result.put("plans", buildPlans());
        return R.ok(result);
    }

    /**
     * 开通/续费会员
     * POST /api/consumer/vip/purchase?plan=monthly|quarterly|yearly
     */
    @PostMapping("/vip/purchase")
    @RequireRole(0)
    public R<Map<String, Object>> purchaseVip(@RequestParam String plan) {
        Long userId = SecurityUtil.getCurrentUserId();
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");

        int months;
        int vipLevel;
        BigDecimal price;
        String planLabel;

        switch (plan) {
            case "monthly":
                months = 1; vipLevel = 1; price = new BigDecimal("9.90"); planLabel = "月会员"; break;
            case "quarterly":
                months = 3; vipLevel = 2; price = new BigDecimal("24.90"); planLabel = "季度会员"; break;
            case "yearly":
                months = 12; vipLevel = 3; price = new BigDecimal("88.00"); planLabel = "年度会员"; break;
            default:
                throw new BusinessException("无效的套餐类型：" + plan);
        }

        // 计算到期时间（如已是会员则续期叠加）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime base = (user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(now))
                ? user.getVipExpireTime()
                : now;
        LocalDateTime newExpire = base.plusMonths(months);

        // 更新用户
        user.setVipLevel(vipLevel);
        user.setVipExpireTime(newExpire);
        userMapper.updateById(user);

        log.info("用户[{}] 开通 {}，到期时间：{}，费用：¥{}", userId, planLabel, newExpire, price);

        Map<String, Object> result = new HashMap<>();
        result.put("vipLevel", vipLevel);
        result.put("planLabel", planLabel);
        result.put("price", price);
        result.put("vipExpireTime", newExpire);
        result.put("benefit", "满30元免运费（快递¥6/外卖配送¥10）");
        result.put("message", planLabel + "开通成功！满30元即享免运费特权");
        return R.ok("开通成功", result);
    }

    /**
     * 构建套餐列表（供前端展示）
     */
    private Object buildPlans() {
        Map<String, Object>[] plans = new Map[3];

        Map<String, Object> monthly = new HashMap<>();
        monthly.put("key", "monthly"); monthly.put("label", "月会员");
        monthly.put("price", "9.90"); monthly.put("months", 1);
        monthly.put("pricePerMonth", "9.90"); monthly.put("recommended", false);
        plans[0] = monthly;

        Map<String, Object> quarterly = new HashMap<>();
        quarterly.put("key", "quarterly"); quarterly.put("label", "季度会员");
        quarterly.put("price", "24.90"); quarterly.put("months", 3);
        quarterly.put("pricePerMonth", "8.30"); quarterly.put("recommended", true);
        quarterly.put("badge", "省5.8元");
        plans[1] = quarterly;

        Map<String, Object> yearly = new HashMap<>();
        yearly.put("key", "yearly"); yearly.put("label", "年度会员");
        yearly.put("price", "88.00"); yearly.put("months", 12);
        yearly.put("pricePerMonth", "7.33"); yearly.put("recommended", false);
        yearly.put("badge", "省30.8元");
        plans[2] = yearly;

        return plans;
    }
}
