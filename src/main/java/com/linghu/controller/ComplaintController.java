package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.linghu.annotation.RequireRole;
import com.linghu.common.R;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 吐槽模块 Controller
 * 消费者端   /api/complaint/**
 * 仓主端     /api/warehouse/complaint/**
 * 运营后台   /api/admin/complaint/**
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintMapper complaintMapper;
    private final ComplaintReplyMapper replyMapper;
    private final UserMapper userMapper;
    private final WarehouseMapper warehouseMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    // ═══════════════════════════════════════════════════════════════
    // 消费者端
    // ═══════════════════════════════════════════════════════════════

    /**
     * POST /api/complaint/create  提交吐槽（支持免登录）
     */
    @PostMapping("/api/complaint/create")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Long userId = null;
        try { userId = SecurityUtil.getCurrentUserId(); } catch (Exception ignored) {}

        String content = (String) body.get("content");
        if (content == null || content.trim().length() < 10) {
            return R.fail("内容不能少于10字");
        }
        if (content.length() > 500) {
            return R.fail("内容不能超过500字");
        }

        Complaint c = new Complaint();
        c.setUserId(userId);
        c.setPhone((String) body.get("phone"));
        c.setContent(content.trim());
        c.setImages((String) body.get("images"));
        c.setVoiceUrl((String) body.get("voice_url"));
        String relatedType = (String) body.get("related_type");
        c.setRelatedType(relatedType);
        Long relatedId = null;
        if (body.get("related_id") != null) {
            relatedId = ((Number) body.get("related_id")).longValue();
            c.setRelatedId(relatedId);
        }
        // 冗余订单号
        if (body.get("order_sn") != null) {
            c.setOrderSn((String) body.get("order_sn"));
        }
        // 自动从订单中解析 warehouseId / brandId
        if ("ORDER".equals(relatedType) && relatedId != null) {
            List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, relatedId));
            if (!items.isEmpty()) {
                // 取第一个 item 的仓库和品牌方（多仓拆单时取首个）
                c.setWarehouseId(items.get(0).getWarehouseId());
                c.setBrandId(items.get(0).getBrandId());
            }
        } else if ("PRODUCT".equals(relatedType) && relatedId != null) {
            // 关联商品时，解析品牌方
            Product p = new Product();
            // 通过 productId 查品牌方（需要 ProductMapper，这里用 body 传入的 brand_id 兜底）
            if (body.get("brand_id") != null) {
                c.setBrandId(((Number) body.get("brand_id")).longValue());
            }
            if (body.get("warehouse_id") != null) {
                c.setWarehouseId(((Number) body.get("warehouse_id")).longValue());
            }
        } else {
            // 直接传入的 warehouseId/brandId
            if (body.get("warehouse_id") != null) {
                c.setWarehouseId(((Number) body.get("warehouse_id")).longValue());
            }
            if (body.get("brand_id") != null) {
                c.setBrandId(((Number) body.get("brand_id")).longValue());
            }
        }
        c.setIsUrgent(Boolean.TRUE.equals(body.get("is_urgent")) ? 1 : 0);
        c.setIsAnonymous(Boolean.TRUE.equals(body.get("is_anonymous")) ? 1 : 0);
        c.setAiUrgent(0);
        c.setStatus("PENDING");
        c.setOverdue(0);
        c.setCompensated(0);
        // 回复截止时间 = 创建时间 + 4小时
        c.setReplyDeadline(LocalDateTime.now().plusHours(4));

        // 简单 AI 紧急识别（关键词匹配，可后续替换为真实 AI 调用）
        String[] urgentKeywords = {"气死", "再也不", "投诉", "赔偿", "维权", "曝光", "退款", "太差了"};
        for (String kw : urgentKeywords) {
            if (content.contains(kw)) {
                c.setAiUrgent(1);
                break;
            }
        }

        // 简单 AI 分类（关键词匹配）
        c.setAiCategory(classifyContent(content));
        c.setAiConfidence(0.8);

        complaintMapper.insert(c);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", c.getId());
        result.put("status", c.getStatus());
        result.put("isUrgent", c.getIsUrgent() == 1 || c.getAiUrgent() == 1);
        return R.ok("吐槽已提交，我们会尽快处理", result);
    }

    /**
     * GET /api/complaint/my-list  我的吐槽列表
     */
    @GetMapping("/api/complaint/my-list")
    @RequireRole(0)
    public R<Map<String, Object>> myList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {

        Long userId = SecurityUtil.getCurrentUserId();
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getUserId, userId)
                .eq(Complaint::getDeleted, 0)
                .orderByDesc(Complaint::getCreateTime);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Complaint::getStatus, status);
        }

        IPage<Complaint> pageResult = complaintMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = pageResult.getRecords().stream()
                .map(this::toConsumerVO)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("records", records);
        return R.ok(result);
    }

    /**
     * GET /api/complaint/detail/{id}  吐槽详情（含回复列表）
     */
    @GetMapping("/api/complaint/detail/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Complaint c = complaintMapper.selectById(id);
        if (c == null || c.getDeleted() == 1) return R.fail("吐槽不存在");

        Map<String, Object> vo = toConsumerVO(c);

        // 附带回复列表
        List<ComplaintReply> replies = replyMapper.selectList(
                new LambdaQueryWrapper<ComplaintReply>()
                        .eq(ComplaintReply::getComplaintId, id)
                        .eq(ComplaintReply::getDeleted, 0)
                        .orderByAsc(ComplaintReply::getCreateTime));
        vo.put("replies", replies.stream().map(r -> {
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("id", r.getId());
            rv.put("content", r.getContent());
            rv.put("images", r.getImages());
            rv.put("replierRole", r.getReplierRole());
            rv.put("isAuto", r.getIsAuto());
            rv.put("createTime", r.getCreateTime());
            return rv;
        }).collect(Collectors.toList()));

        return R.ok(vo);
    }

    /**
     * POST /api/complaint/satisfaction  满意度评价
     */
    @PostMapping("/api/complaint/satisfaction")
    @RequireRole(0)
    public R<String> satisfaction(@RequestBody Map<String, Object> body) {
        Long complaintId = ((Number) body.get("complaint_id")).longValue();
        int rating = ((Number) body.get("rating")).intValue(); // 1=满意 2=一般 3=不满意

        Complaint c = complaintMapper.selectById(complaintId);
        if (c == null) return R.fail("吐槽不存在");
        if (c.getSatisfaction() != null) return R.fail("已评价，不可重复");

        Complaint update = new Complaint();
        update.setId(complaintId);
        update.setSatisfaction(rating);
        update.setSatComment((String) body.get("comment"));
        update.setSatTime(LocalDateTime.now());
        update.setStatus("RESOLVED");
        complaintMapper.updateById(update);
        return R.ok("评价成功");
    }

    // ═══════════════════════════════════════════════════════════════
    // 仓主端
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET /api/warehouse/complaint/list  我的仓相关吐槽
     */
    @GetMapping("/api/warehouse/complaint/list")
    @RequireRole(1)
    public R<Map<String, Object>> warehouseList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {

        Long userId = SecurityUtil.getCurrentUserId();
        Warehouse warehouse = warehouseMapper.selectOne(
                new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getUserId, userId)
                        .eq(Warehouse::getDeleted, 0)
                        .last("LIMIT 1"));
        if (warehouse == null) return R.fail("未找到仓库信息");

        // 同时匹配：关联类型=WAREHOUSE 且 relatedId=仓库ID，或者 warehouseId=仓库ID（订单吐槽）
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getDeleted, 0)
                .and(w -> w
                    .eq(Complaint::getWarehouseId, warehouse.getId())
                    .or()
                    .nested(n -> n
                        .eq(Complaint::getRelatedType, "WAREHOUSE")
                        .eq(Complaint::getRelatedId, warehouse.getId())))
                .orderByDesc(Complaint::getIsUrgent)
                .orderByDesc(Complaint::getAiUrgent)
                .orderByDesc(Complaint::getCreateTime);
        if (status != null && !status.isEmpty()) wrapper.eq(Complaint::getStatus, status);

        IPage<Complaint> pageResult = complaintMapper.selectPage(new Page<>(page, size), wrapper);

        // 统计各状态数量
        long urgentCount = complaintMapper.selectCount(
                new LambdaQueryWrapper<Complaint>()
                        .eq(Complaint::getRelatedType, "WAREHOUSE")
                        .eq(Complaint::getRelatedId, warehouse.getId())
                        .eq(Complaint::getDeleted, 0)
                        .eq(Complaint::getStatus, "PENDING")
                        .and(w -> w.eq(Complaint::getIsUrgent, 1).or().eq(Complaint::getAiUrgent, 1)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("urgentCount", urgentCount);
        result.put("records", pageResult.getRecords().stream().map(this::toWarehouseVO).collect(Collectors.toList()));
        return R.ok(result);
    }

    /**
     * POST /api/warehouse/complaint/reply/{id}  仓主回复吐槽
     */
    @PostMapping("/api/warehouse/complaint/reply/{id}")
    @RequireRole(1)
    public R<String> warehouseReply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        String content = (String) body.get("content");
        if (content == null || content.trim().isEmpty()) return R.fail("回复内容不能为空");
        if (content.length() > 500) return R.fail("回复不能超过500字");

        Complaint c = complaintMapper.selectById(id);
        if (c == null || c.getDeleted() == 1) return R.fail("吐槽不存在");

        ComplaintReply reply = new ComplaintReply();
        reply.setComplaintId(id);
        reply.setReplierId(userId);
        reply.setReplierRole(1);
        reply.setContent(content.trim());
        reply.setImages((String) body.get("images"));
        reply.setIsAuto(0);
        replyMapper.insert(reply);

        // 更新吐槽状态
        Complaint update = new Complaint();
        update.setId(id);
        update.setStatus("REPLIED");
        complaintMapper.updateById(update);

        return R.ok("回复成功");
    }

    /**
     * PUT /api/warehouse/complaint/status/{id}  仓主更新处理状态
     */
    @PutMapping("/api/warehouse/complaint/status/{id}")
    @RequireRole(1)
    public R<String> warehouseUpdateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String newStatus = (String) body.get("status");
        if (!Arrays.asList("PROCESSING", "REPLIED", "RESOLVED").contains(newStatus)) {
            return R.fail("无效状态值");
        }
        Complaint update = new Complaint();
        update.setId(id);
        update.setStatus(newStatus);
        complaintMapper.updateById(update);
        return R.ok("状态已更新");
    }

    // ═══════════════════════════════════════════════════════════════
    // 运营后台
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET /api/admin/complaint/list  全量吐槽列表
     */
    @GetMapping("/api/admin/complaint/list")
    @RequireRole(9)
    public R<Map<String, Object>> adminList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String relatedType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String urgent) {

        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getDeleted, 0)
                .orderByDesc(Complaint::getIsUrgent)
                .orderByDesc(Complaint::getAiUrgent)
                .orderByDesc(Complaint::getCreateTime);

        if (status != null && !status.isEmpty()) wrapper.eq(Complaint::getStatus, status);
        if (relatedType != null && !relatedType.isEmpty()) wrapper.eq(Complaint::getRelatedType, relatedType);
        if (keyword != null && !keyword.isEmpty()) wrapper.like(Complaint::getContent, keyword);
        if ("1".equals(urgent)) {
            wrapper.and(w -> w.eq(Complaint::getIsUrgent, 1).or().eq(Complaint::getAiUrgent, 1));
        }

        // 超时检测：自动标记超时
        LocalDateTime now = LocalDateTime.now();
        IPage<Complaint> pageResult = complaintMapper.selectPage(new Page<>(page, size), wrapper);
        pageResult.getRecords().forEach(c -> {
            if (c.getOverdue() == 0 && c.getReplyDeadline() != null
                    && now.isAfter(c.getReplyDeadline())
                    && "PENDING".equals(c.getStatus())) {
                Complaint upd = new Complaint();
                upd.setId(c.getId());
                upd.setOverdue(1);
                complaintMapper.updateById(upd);
                c.setOverdue(1);
            }
        });

        // 汇总数字
        long totalCount  = complaintMapper.selectCount(new LambdaQueryWrapper<Complaint>().eq(Complaint::getDeleted, 0));
        long pendingCount = complaintMapper.selectCount(new LambdaQueryWrapper<Complaint>().eq(Complaint::getDeleted, 0).eq(Complaint::getStatus, "PENDING"));
        long overdueCount = complaintMapper.selectCount(new LambdaQueryWrapper<Complaint>().eq(Complaint::getDeleted, 0).eq(Complaint::getOverdue, 1));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("totalCount", totalCount);
        result.put("pendingCount", pendingCount);
        result.put("overdueCount", overdueCount);
        result.put("records", pageResult.getRecords().stream().map(this::toAdminVO).collect(Collectors.toList()));
        return R.ok(result);
    }

    /**
     * PUT /api/admin/complaint/assign/{id}  分配处理人
     */
    @PutMapping("/api/admin/complaint/assign/{id}")
    @RequireRole(9)
    public R<String> assign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long assignedTo = ((Number) body.get("assignedTo")).longValue();
        Complaint update = new Complaint();
        update.setId(id);
        update.setAssignedTo(assignedTo);
        update.setStatus("PROCESSING");
        complaintMapper.updateById(update);
        return R.ok("已分配");
    }

    /**
     * POST /api/admin/complaint/compensate  运营回复 + 标记已补偿
     */
    @PostMapping("/api/admin/complaint/compensate")
    @RequireRole(9)
    public R<String> compensate(@RequestBody Map<String, Object> body) {
        Long complaintId = ((Number) body.get("complaint_id")).longValue();
        String content = (String) body.get("content");

        // 添加运营回复
        if (content != null && !content.trim().isEmpty()) {
            Long userId = SecurityUtil.getCurrentUserId();
            ComplaintReply reply = new ComplaintReply();
            reply.setComplaintId(complaintId);
            reply.setReplierId(userId);
            reply.setReplierRole(9);
            reply.setContent(content.trim());
            reply.setIsAuto(0);
            replyMapper.insert(reply);
        }

        Complaint update = new Complaint();
        update.setId(complaintId);
        update.setCompensated(1);
        update.setStatus("REPLIED");
        complaintMapper.updateById(update);
        return R.ok("补偿已记录");
    }

    /**
     * GET /api/admin/complaint/keywords  高频关键词统计
     */
    @GetMapping("/api/admin/complaint/keywords")
    @RequireRole(9)
    public R<List<Map<String, Object>>> keywords() {
        // 取最近 500 条吐槽内容做词频统计
        List<Complaint> recent = complaintMapper.selectList(
                new LambdaQueryWrapper<Complaint>()
                        .eq(Complaint::getDeleted, 0)
                        .orderByDesc(Complaint::getCreateTime)
                        .last("LIMIT 500"));

        Map<String, Integer> freq = new LinkedHashMap<>();
        String[] stopWords = {"的", "了", "是", "我", "你", "他", "也", "在", "就", "都", "和", "有", "不", "很", "这", "那"};
        Set<String> stop = new HashSet<>(Arrays.asList(stopWords));

        for (Complaint c : recent) {
            String text = c.getContent();
            // 简单 2-4 字词频统计
            for (int len = 2; len <= 4; len++) {
                for (int i = 0; i <= text.length() - len; i++) {
                    String word = text.substring(i, i + len);
                    if (!stop.contains(word) && word.matches("[\\u4e00-\\u9fa5]+")) {
                        freq.merge(word, 1, Integer::sum);
                    }
                }
            }
        }

        List<Map<String, Object>> result = freq.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(50)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("word", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
        return R.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // 私有工具方法
    // ═══════════════════════════════════════════════════════════════

    private String classifyContent(String content) {
        if (content.matches(".*(变质|腐烂|过期|破损|碎了|漏了|包装).* ")) return "商品质量";
        if (content.matches(".*(漏发|少发|拣货|错发|态度).* ")) return "仓主服务";
        if (content.matches(".*(超时|送晚|骑手|外卖员).* ")) return "骑手配送";
        if (content.matches(".*(APP|支付|卡顿|闪退|bug|功能).* ")) return "平台功能";
        if (content.matches(".*(建议|希望|能不能|可以加|改进).* ")) return "建议";
        // 关键词匹配（更宽泛）
        if (content.contains("变质") || content.contains("过期") || content.contains("破损") || content.contains("碎")) return "商品质量";
        if (content.contains("漏发") || content.contains("少") || content.contains("错")) return "仓主服务";
        if (content.contains("超时") || content.contains("送晚") || content.contains("骑手")) return "骑手配送";
        if (content.contains("建议") || content.contains("希望")) return "建议";
        return "纯情绪发泄";
    }

    private Map<String, Object> toConsumerVO(Complaint c) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", c.getId());
        vo.put("content", c.getContent());
        vo.put("images", c.getImages());
        vo.put("relatedType", c.getRelatedType());
        vo.put("relatedId", c.getRelatedId());
        vo.put("orderSn", c.getOrderSn());
        vo.put("warehouseId", c.getWarehouseId());
        vo.put("brandId", c.getBrandId());
        vo.put("isUrgent", c.getIsUrgent() == 1 || c.getAiUrgent() == 1);
        vo.put("status", c.getStatus());
        vo.put("aiCategory", c.getAiCategory());
        vo.put("satisfaction", c.getSatisfaction());
        vo.put("compensated", c.getCompensated());
        vo.put("createTime", c.getCreateTime());
        return vo;
    }

    private Map<String, Object> toWarehouseVO(Complaint c) {
        Map<String, Object> vo = toConsumerVO(c);
        // 匿名处理：不显示用户信息
        if (c.getIsAnonymous() == 1) {
            vo.put("userName", "匿名用户");
        } else if (c.getUserId() != null) {
            User u = userMapper.selectById(c.getUserId());
            vo.put("userName", u != null ? u.getUsername() : "用户" + c.getUserId());
        }
        vo.put("overdue", c.getOverdue());
        vo.put("replyDeadline", c.getReplyDeadline());
        return vo;
    }

    private Map<String, Object> toAdminVO(Complaint c) {
        Map<String, Object> vo = toWarehouseVO(c);
        vo.put("userId", c.getUserId());
        vo.put("phone", c.getPhone());
        vo.put("aiUrgent", c.getAiUrgent());
        vo.put("aiConfidence", c.getAiConfidence());
        vo.put("assignedTo", c.getAssignedTo());
        return vo;
    }

    // ═══════════════════════════════════════════════════════════════
    // 品牌方端
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET /api/brand/complaint/list  品牌方查看与自己商品相关的吐槽
     */
    @GetMapping("/api/brand/complaint/list")
    @RequireRole(2)
    public R<Map<String, Object>> brandList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {

        Long userId = SecurityUtil.getCurrentUserId();
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getBrandId, userId)
                .eq(Complaint::getDeleted, 0)
                .orderByDesc(Complaint::getIsUrgent)
                .orderByDesc(Complaint::getCreateTime);
        if (status != null && !status.isEmpty()) wrapper.eq(Complaint::getStatus, status);

        IPage<Complaint> pageResult = complaintMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = pageResult.getRecords().stream()
                .map(this::toWarehouseVO)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", pageResult.getTotal());
        return R.ok(result);
    }

    /**
     * POST /api/brand/complaint/reply/{id}  品牌方回复吐槽
     */
    @PostMapping("/api/brand/complaint/reply/{id}")
    @RequireRole(2)
    public R<String> brandReply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        Complaint c = complaintMapper.selectById(id);
        if (c == null) return R.fail("吐槽不存在");

        ComplaintReply reply = new ComplaintReply();
        reply.setComplaintId(id);
        reply.setReplierId(userId);
        reply.setReplierRole(2); // 品牌方
        reply.setContent((String) body.get("content"));
        reply.setIsAuto(0);
        replyMapper.insert(reply);

        Complaint update = new Complaint();
        update.setId(id);
        update.setStatus("REPLIED");
        complaintMapper.updateById(update);
        return R.ok("回复成功");
    }
}
