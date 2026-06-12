package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/consumer/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityMapper activityMapper;
    private final ActivityInviteMapper activityInviteMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final WarehouseMapper warehouseMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final InventoryMapper inventoryMapper;
    private final WalletMapper walletMapper;

    /**
     * 获取当前正在进行的活动列表（不需要登录）
     * GET /api/consumer/activity/list
     */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> getActivityList() {
        LocalDateTime now = LocalDateTime.now();
        List<Activity> activities = activityMapper.selectList(
            new LambdaQueryWrapper<Activity>()
                .eq(Activity::getStatus, 1)
                .eq(Activity::getDeleted, 0)
                .le(Activity::getStartTime, now)
                .ge(Activity::getEndTime, now)
                .orderByDesc(Activity::getCreateTime)
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (Activity a : activities) {
            Product p = productMapper.selectById(a.getProductId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());          // Flutter 前端读取字段
            item.put("activityId", a.getId());  // 保留兼容
            item.put("name", a.getName());
            item.put("activityPrice", a.getActivityPrice());
            item.put("originalPrice", a.getOriginalPrice());
            item.put("description", a.getDescription());
            item.put("endTime", a.getEndTime());
            item.put("productId", a.getProductId());
            if (p != null) {
                item.put("productName", p.getName());
                item.put("productImage", p.getImages());
            }
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 通过邀请码获取活动详情（落地页直接调用，不需要登录）
     * GET /api/consumer/activity/by-invite-code?inviteCode=xxx
     */
    @GetMapping("/by-invite-code")
    public R<Map<String, Object>> getActivityByInviteCode(
            @RequestParam String inviteCode) {

        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BusinessException("邀请码不能为空");
        }

        // 通过邀请码找到邀请记录
        ActivityInvite invite = activityInviteMapper.selectOne(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getInviteCode, inviteCode));
        if (invite == null) {
            throw new BusinessException("无效的邀请码");
        }

        Activity activity = activityMapper.selectById(invite.getActivityId());
        if (activity == null || activity.getDeleted() == 1 || activity.getStatus() == 0) {
            throw new BusinessException("活动不存在或已结束");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BusinessException("活动已结束");
        }

        Product p = productMapper.selectById(activity.getProductId());
        User inviter = userMapper.selectById(invite.getInviterId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", activity.getId());
        result.put("activityId", activity.getId());
        result.put("activityName", activity.getName());
        result.put("name", activity.getName());
        result.put("activityPrice", activity.getActivityPrice());
        result.put("originalPrice", activity.getOriginalPrice());
        result.put("description", activity.getDescription());
        result.put("endTime", activity.getEndTime());
        result.put("productId", activity.getProductId());
        result.put("inviteCode", inviteCode);
        result.put("inviterName", inviter != null ? inviter.getUsername() : "好友");
        if (p != null) {
            result.put("productName", p.getName());
            result.put("productImage", p.getImages());
        }
        return R.ok(result);
    }

    /**
     * 获取活动详情（含商品信息，用于分享落地页）
     * GET /api/consumer/activity/{activityId}/detail?inviteCode=xxx
     * 不需要登录
     */
    @GetMapping("/{activityId}/detail")
    public R<Map<String, Object>> getActivityDetail(
            @PathVariable Long activityId,
            @RequestParam(required = false) String inviteCode) {

        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1 || activity.getStatus() == 0) {
            throw new BusinessException("活动不存在或已结束");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BusinessException("活动已结束");
        }

        Product p = productMapper.selectById(activity.getProductId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", activity.getId());
        result.put("name", activity.getName());
        result.put("activityPrice", activity.getActivityPrice());
        result.put("originalPrice", activity.getOriginalPrice());
        result.put("description", activity.getDescription());
        result.put("endTime", activity.getEndTime());
        result.put("productId", activity.getProductId());
        result.put("totalQuota", activity.getTotalQuota());
        result.put("usedQuota", activity.getUsedQuota());
        if (p != null) {
            result.put("productName", p.getName());
            result.put("productImage", p.getImages());
        }
        // 邀请人信息（用于落地页展示"XXX邀请你来"）
        if (inviteCode != null && !inviteCode.isBlank()) {
            ActivityInvite invite = activityInviteMapper.selectOne(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getInviteCode, inviteCode));
            if (invite != null) {
                User inviter = userMapper.selectById(invite.getInviterId());
                result.put("inviteCode", inviteCode);
                result.put("inviterName", inviter != null ? inviter.getUsername() : "好友");
            }
        }
        return R.ok(result);
    }

    /**
     * 发起邀请（生成邀请码）
     * POST /api/consumer/activity/{activityId}/invite
     * 需要登录（消费者）
     */
    @PostMapping("/{activityId}/invite")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> createInvite(@PathVariable Long activityId) {
        Long userId = SecurityUtil.getCurrentUserId();

        Activity activity = activityMapper.selectById(activityId);
        validateActivity(activity);

        // 检查邀请次数（只统计被邀请人已注册/已下单的有效邀请，PENDING且无invitee的不计入）
        long inviteCount = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getActivityId, activityId)
                .eq(ActivityInvite::getInviterId, userId)
                .and(w -> w.isNotNull(ActivityInvite::getInviteeId)
                    .or().eq(ActivityInvite::getStatus, "PICKED_UP")
                    .or().eq(ActivityInvite::getStatus, "ORDERED")));
        if (inviteCount >= activity.getMaxInvitePerUser()) {
            throw new BusinessException("您已达到本活动邀请次数上限（" + activity.getMaxInvitePerUser() + "次）");
        }

        // 检查名额
        if (activity.getTotalQuota() > 0 && activity.getUsedQuota() >= activity.getTotalQuota()) {
            throw new BusinessException("活动名额已用完");
        }

        // 生成唯一邀请码
        String inviteCode = generateInviteCode(userId, activityId);

        // 插入邀请记录
        ActivityInvite invite = new ActivityInvite();
        invite.setActivityId(activityId);
        invite.setInviterId(userId);
        invite.setInviteCode(inviteCode);
        invite.setStatus("PENDING");
        activityInviteMapper.insert(invite);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inviteCode", inviteCode);
        result.put("inviteId", invite.getId());
        result.put("shareUrl", "/activity/" + activityId + "?inviteCode=" + inviteCode);
        result.put("activityName", activity.getName());
        result.put("activityPrice", activity.getActivityPrice());
        result.put("originalPrice", activity.getOriginalPrice());
        result.put("description", activity.getDescription());

        Product p = productMapper.selectById(activity.getProductId());
        if (p != null) {
            result.put("productName", p.getName());
            result.put("productImage", p.getImages());
        }
        return R.ok("邀请链接已生成", result);
    }

    /**
     * 新用户通过邀请码完成绑定（注册后调用）
     * POST /api/consumer/activity/bind-invite
     * Body: { "inviteCode": "xxx" }
     * 需要登录（消费者，刚注册的新用户）
     */
    @PostMapping("/bind-invite")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> bindInvite(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        String inviteCode = body.get("inviteCode");
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BusinessException("邀请码不能为空");
        }

        ActivityInvite invite = activityInviteMapper.selectOne(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getInviteCode, inviteCode));
        if (invite == null) {
            throw new BusinessException("无效的邀请码");
        }
        if (!"PENDING".equals(invite.getStatus())) {
            throw new BusinessException("该邀请链接已被使用");
        }
        if (invite.getInviterId().equals(userId)) {
            throw new BusinessException("不能邀请自己");
        }

        // 检查是否已通过其他邀请绑定过此活动
        long existingBinding = activityInviteMapper.selectCount(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getActivityId, invite.getActivityId())
                .eq(ActivityInvite::getInviteeId, userId));
        if (existingBinding > 0) {
            throw new BusinessException("您已参与过此活动");
        }

        Activity activity = activityMapper.selectById(invite.getActivityId());
        validateActivity(activity);

        invite.setInviteeId(userId);
        invite.setStatus("REGISTERED");
        activityInviteMapper.updateById(invite);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", invite.getActivityId());
        result.put("productId", activity.getProductId());
        result.put("activityPrice", activity.getActivityPrice());
        result.put("inviteCode", inviteCode);

        Product p = productMapper.selectById(activity.getProductId());
        if (p != null) {
            result.put("productName", p.getName());
            result.put("productImage", p.getImages());
        }
        return R.ok("绑定成功，已享有0.1元购买资格", result);
    }

    /**
     * 活动商品下单（0.1元购，仅限自提）
     * POST /api/consumer/activity/{activityId}/order
     * Body: { "inviteCode": "xxx", "warehouseId": 1 }
     * role=inviter时 inviteCode可为null（直接邀请人自己下单）
     */
    @PostMapping("/{activityId}/order")
    @RequireRole(0)
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> placeActivityOrder(
            @PathVariable Long activityId,
            @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();

        String inviteCode = (String) body.get("inviteCode");
        Long warehouseId = body.get("warehouseId") != null
            ? ((Number) body.get("warehouseId")).longValue() : null;
        if (warehouseId == null) throw new BusinessException("请选择自提仓库");

        Activity activity = activityMapper.selectById(activityId);
        validateActivity(activity);

        // 验证资格（邀请人或被邀请人）
        ActivityInvite invite = null;
        boolean isInviter = false;

        if (inviteCode != null && !inviteCode.isBlank()) {
            // 被邀请人下单
            invite = activityInviteMapper.selectOne(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getInviteCode, inviteCode)
                    .eq(ActivityInvite::getInviteeId, userId));
            if (invite == null) {
                throw new BusinessException("邀请码无效或不属于您");
            }
            if (!"REGISTERED".equals(invite.getStatus())) {
                throw new BusinessException("该邀请码状态不允许下单");
            }
        } else {
            // 邀请人自己下单（PENDING/REGISTERED/ORDERED 均可，无需等好友注册）
            List<ActivityInvite> myInvites = activityInviteMapper.selectList(
                new LambdaQueryWrapper<ActivityInvite>()
                    .eq(ActivityInvite::getActivityId, activityId)
                    .eq(ActivityInvite::getInviterId, userId)
                    .in(ActivityInvite::getStatus, Arrays.asList("PENDING", "REGISTERED", "ORDERED")));
            if (myInvites.isEmpty()) {
                throw new BusinessException("您还没有发起过邀请，请先生成邀请码");
            }
            // 找邀请人尚未下单的记录（inviterOrderId 为空）
            invite = myInvites.stream()
                .filter(i -> i.getInviterOrderId() == null)
                .findFirst()
                .orElseThrow(() -> new BusinessException("您已为该活动下过单，无需重复下单"));
            isInviter = true;
        }

        // 检查仓库是否有库存
        Inventory inv = inventoryMapper.selectOne(
            new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWarehouseId, warehouseId)
                .eq(Inventory::getProductId, activity.getProductId())
                .eq(Inventory::getDeleted, 0));
        if (inv == null || (inv.getQuantity() - inv.getLockedQuantity()) < 1) {
            throw new BusinessException("该仓库此商品库存不足，请选择其他仓库");
        }

        // 钱包扣款0.1元
        BigDecimal activityPrice = activity.getActivityPrice();
        Wallet wallet = walletMapper.selectOne(
            new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId));
        if (wallet == null || wallet.getBalance().compareTo(activityPrice) < 0) {
            throw new BusinessException("钱包余额不足，请先充值（需至少¥" + activityPrice.toPlainString() + "）");
        }
        walletMapper.updateBalance(userId, activityPrice.negate());

        // 锁定库存
        inventoryMapper.lockInventory(warehouseId, activity.getProductId(), 1);

        // 创建订单（活动订单，自提）
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderSn(generateOrderSn("ACT"));
        order.setGoodsAmount(activityPrice);
        order.setShippingFee(BigDecimal.ZERO);
        order.setTotalAmount(activityPrice);
        order.setStatus("PENDING_DELIVERY");
        order.setDeliveryMode("pickup");
        order.setPaidAt(LocalDateTime.now());
        order.setDeleted(0);
        orderMapper.insert(order);

        OrderItem oi = new OrderItem();
        oi.setOrderId(order.getId());
        oi.setProductId(activity.getProductId());
        oi.setWarehouseId(warehouseId);
        oi.setQuantity(1);
        oi.setPrice(activityPrice);
        Product p = productMapper.selectById(activity.getProductId());
        oi.setBrandId(p != null ? p.getBrandId() : 0L);
        orderItemMapper.insert(oi);

        // 生成自提码（6位数字）
        String pickUpCode = generatePickUpCode();
        String pickUpQr = invite.getId() + ":" + pickUpCode;

        // 更新邀请记录
        if (isInviter) {
            invite.setInviterOrderId(order.getId());
            invite.setPickUpCode(pickUpCode);
            invite.setPickUpQr(pickUpQr);
            invite.setWarehouseId(warehouseId);
            // 邀请人下单后：若被邀请人已下单 → ORDERED，否则保持原状（PENDING/REGISTERED）
            if (invite.getInviteeOrderId() != null) {
                invite.setStatus("ORDERED");
            }
            // PENDING/REGISTERED 状态不变，等好友注册/下单后再流转
        } else {
            invite.setInviteeOrderId(order.getId());
            invite.setPickUpCode(pickUpCode);
            invite.setPickUpQr(pickUpQr);
            invite.setWarehouseId(warehouseId);
            invite.setStatus("ORDERED");
        }
        activityInviteMapper.updateById(invite);

        // 递增活动名额
        activityInviteMapper.incrementUsedQuota(activityId);

        Warehouse w = warehouseMapper.selectById(warehouseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("orderSn", order.getOrderSn());
        result.put("pickUpCode", pickUpCode);
        result.put("pickUpQr", pickUpQr);
        result.put("warehouseName", w != null ? w.getName() : "");
        result.put("warehouseAddress", w != null ? w.getAddress() : "");
        result.put("productName", p != null ? p.getName() : "");
        result.put("activityPrice", activityPrice);
        return R.ok("下单成功，请到仓库凭自提码取货", result);
    }

    /**
     * 我的邀请记录
     * GET /api/consumer/activity/my-invites
     */
    @GetMapping("/my-invites")
    @RequireRole(0)
    public R<List<Map<String, Object>>> getMyInvites() {
        Long userId = SecurityUtil.getCurrentUserId();
        List<ActivityInvite> invites = activityInviteMapper.selectList(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getInviterId, userId)
                .orderByDesc(ActivityInvite::getCreateTime));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ActivityInvite i : invites) {
            Activity a = activityMapper.selectById(i.getActivityId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("inviteId", i.getId());
            item.put("inviteCode", i.getInviteCode());
            item.put("status", i.getStatus());
            item.put("statusText", getInviteStatusText(i.getStatus()));
            item.put("pickUpCode", i.getPickUpCode());
            item.put("createTime", i.getCreateTime());
            item.put("pickedUpAt", i.getPickedUpAt());
            if (a != null) {
                item.put("activityId", a.getId());
                item.put("activityName", a.getName());
                item.put("activityPrice", a.getActivityPrice());
                Product p = productMapper.selectById(a.getProductId());
                if (p != null) {
                    item.put("productName", p.getName());
                    item.put("productImage", p.getImages());
                }
            }
            // 被邀请人信息
            if (i.getInviteeId() != null) {
                User invitee = userMapper.selectById(i.getInviteeId());
                item.put("inviteeName", invitee != null ? invitee.getUsername() : "");
            }
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 查询我的活动自提码（已下单的，展示给用户去核销）
     * GET /api/consumer/activity/my-pickup-codes
     */
    @GetMapping("/my-pickup-codes")
    @RequireRole(0)
    public R<List<Map<String, Object>>> getMyPickupCodes() {
        Long userId = SecurityUtil.getCurrentUserId();

        // 作为邀请人或被邀请人均可能有自提码
        List<ActivityInvite> asInviter = activityInviteMapper.selectList(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getInviterId, userId)
                .isNotNull(ActivityInvite::getInviterOrderId)
                .in(ActivityInvite::getStatus, Arrays.asList("PENDING", "REGISTERED", "ORDERED", "PICKED_UP")));

        List<ActivityInvite> asInvitee = activityInviteMapper.selectList(
            new LambdaQueryWrapper<ActivityInvite>()
                .eq(ActivityInvite::getInviteeId, userId)
                .isNotNull(ActivityInvite::getInviteeOrderId)
                .in(ActivityInvite::getStatus, Arrays.asList("ORDERED", "PICKED_UP")));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ActivityInvite i : asInviter) {
            result.add(buildPickupCodeItem(i, "inviter", userId));
        }
        for (ActivityInvite i : asInvitee) {
            result.add(buildPickupCodeItem(i, "invitee", userId));
        }
        result.sort((a, b) -> {
            // 待核销排前
            String sa = (String) a.get("status");
            String sb = (String) b.get("status");
            return sa.compareTo(sb);
        });
        return R.ok(result);
    }

    private Map<String, Object> buildPickupCodeItem(ActivityInvite i, String role, Long userId) {
        Activity a = activityMapper.selectById(i.getActivityId());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("inviteId", i.getId());
        item.put("pickUpCode", i.getPickUpCode());
        item.put("pickUpQr", i.getPickUpQr());
        item.put("status", i.getStatus());
        item.put("pickedUpAt", i.getPickedUpAt());
        if (a != null) {
            item.put("activityName", a.getName());
            item.put("activityPrice", a.getActivityPrice());
            Product p = productMapper.selectById(a.getProductId());
            if (p != null) {
                item.put("productName", p.getName());
                item.put("productImage", p.getImages());
            }
        }
        Warehouse w = i.getWarehouseId() != null ? warehouseMapper.selectById(i.getWarehouseId()) : null;
        item.put("warehouseName", w != null ? w.getName() : "");
        item.put("warehouseAddress", w != null ? w.getAddress() : "");
        return item;
    }

    // ========================= 工具 =========================

    private void validateActivity(Activity activity) {
        if (activity == null || activity.getDeleted() == 1 || activity.getStatus() == 0) {
            throw new BusinessException("活动不存在或已关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) throw new BusinessException("活动尚未开始");
        if (now.isAfter(activity.getEndTime())) throw new BusinessException("活动已结束");
    }

    private String generateInviteCode(Long userId, Long activityId) {
        return String.format("%d%d%d", userId, activityId,
            System.currentTimeMillis() % 100000).substring(0, Math.min(16,
            String.format("%d%d%d", userId, activityId, System.currentTimeMillis() % 100000).length()));
    }

    private String generatePickUpCode() {
        return String.format("%06d", (int)(Math.random() * 1000000));
    }

    private String generateOrderSn(String prefix) {
        return prefix + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }

    private String getInviteStatusText(String status) {
        Map<String, String> m = new HashMap<>();
        m.put("PENDING", "等待好友注册");
        m.put("REGISTERED", "好友已注册");
        m.put("ORDERED", "已下单待核销");
        m.put("PICKED_UP", "已核销完成");
        return m.getOrDefault(status, status);
    }
}
