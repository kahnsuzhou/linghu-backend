package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.entity.UserAddress;
import com.linghu.mapper.UserAddressMapper;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 消费者收货地址管理（三端通用，实际仅消费者使用）
 *
 * GET    /api/address/list          查询当前用户地址列表
 * POST   /api/address/add           新增地址
 * PUT    /api/address/update/{id}   更新地址
 * POST   /api/address/set-default/{id}  设为默认
 * DELETE /api/address/{id}          删除地址（逻辑删除）
 */
@Slf4j
@RestController
@RequestMapping("/api/address")
@RequiredArgsConstructor
public class AddressController {

    private final UserAddressMapper addressMapper;

    /**
     * 查询当前用户所有地址，默认地址排在最前
     */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> listAddresses() {
        Long userId = SecurityUtil.getCurrentUserId();

        List<UserAddress> addresses = addressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .orderByDesc(UserAddress::getIsDefault)
                        .orderByDesc(UserAddress::getCreateTime));

        List<Map<String, Object>> result = addresses.stream()
                .map(this::toMap)
                .collect(Collectors.toList());

        return R.ok(result);
    }

    /**
     * 新增地址
     * body: { name, phone, province, city, district, detail, latitude, longitude }
     */
    @PostMapping("/add")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> addAddress(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();

        String name   = (String) body.get("name");
        String phone  = (String) body.get("phone");
        String detail = (String) body.get("detail");

        if (name == null || name.trim().isEmpty()) throw new BusinessException("联系人不能为空");
        if (phone == null || phone.trim().isEmpty()) throw new BusinessException("联系电话不能为空");
        if (detail == null || detail.trim().isEmpty()) throw new BusinessException("详细地址不能为空");

        // 若是第一条地址，自动设为默认
        long count = addressMapper.selectCount(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId));
        boolean setDefault = (count == 0);

        UserAddress addr = new UserAddress();
        addr.setUserId(userId);
        addr.setName(name.trim());
        addr.setPhone(phone.trim());
        addr.setProvince(str(body, "province"));
        addr.setCity(str(body, "city"));
        addr.setDistrict(str(body, "district"));
        addr.setDetail(detail.trim());
        addr.setLatitude(decimal(body, "latitude"));
        addr.setLongitude(decimal(body, "longitude"));
        addr.setIsDefault(setDefault ? 1 : 0);
        addr.setDeleted(0);

        addressMapper.insert(addr);
        log.info("用户[{}] 新增地址 id={}", userId, addr.getId());
        return R.ok("添加成功", toMap(addr));
    }

    /**
     * 更新地址（只允许修改自己的）
     */
    @PutMapping("/update/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> updateAddress(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null || !addr.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在");
        }

        if (body.containsKey("name"))     addr.setName(str(body, "name").trim());
        if (body.containsKey("phone"))    addr.setPhone(str(body, "phone").trim());
        if (body.containsKey("province")) addr.setProvince(str(body, "province"));
        if (body.containsKey("city"))     addr.setCity(str(body, "city"));
        if (body.containsKey("district")) addr.setDistrict(str(body, "district"));
        if (body.containsKey("detail"))   addr.setDetail(str(body, "detail").trim());
        if (body.containsKey("latitude")) addr.setLatitude(decimal(body, "latitude"));
        if (body.containsKey("longitude"))addr.setLongitude(decimal(body, "longitude"));

        addressMapper.updateById(addr);
        log.info("用户[{}] 更新地址 id={}", userId, id);
        return R.ok("更新成功", toMap(addr));
    }

    /**
     * 设为默认地址
     */
    @PostMapping("/set-default/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> setDefault(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null || !addr.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在");
        }

        // 先清除所有默认
        addressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .set(UserAddress::getIsDefault, 0));
        // 设新默认
        addressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getId, id)
                .set(UserAddress::getIsDefault, 1));

        return R.ok("已设为默认地址", null);
    }

    /**
     * 删除地址（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> deleteAddress(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null || !addr.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在");
        }

        addressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getId, id)
                .set(UserAddress::getDeleted, 1));

        // 如果删掉的是默认地址，把最新的一条设为默认
        if (addr.getIsDefault() != null && addr.getIsDefault() == 1) {
            List<UserAddress> remaining = addressMapper.selectList(
                    new LambdaQueryWrapper<UserAddress>()
                            .eq(UserAddress::getUserId, userId)
                            .orderByDesc(UserAddress::getCreateTime)
                            .last("LIMIT 1"));
            if (!remaining.isEmpty()) {
                addressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                        .eq(UserAddress::getId, remaining.get(0).getId())
                        .set(UserAddress::getIsDefault, 1));
            }
        }

        log.info("用户[{}] 删除地址 id={}", userId, id);
        return R.ok("删除成功", null);
    }

    // ── 工具方法 ────────────────────────────────────────────────

    private Map<String, Object> toMap(UserAddress a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("phone", a.getPhone());
        m.put("province", a.getProvince());
        m.put("city", a.getCity());
        m.put("district", a.getDistrict());
        m.put("detail", a.getDetail());
        m.put("latitude", a.getLatitude());
        m.put("longitude", a.getLongitude());
        m.put("isDefault", a.getIsDefault());
        m.put("createTime", a.getCreateTime());
        // 拼合展示用的完整地址
        StringBuilder full = new StringBuilder();
        if (a.getProvince() != null) full.append(a.getProvince());
        if (a.getCity()     != null) full.append(a.getCity());
        if (a.getDistrict() != null) full.append(a.getDistrict());
        full.append(a.getDetail());
        m.put("fullAddress", full.toString());
        return m;
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    private BigDecimal decimal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }
}
