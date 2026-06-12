package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.dto.LoginDTO;
import com.linghu.dto.RegisterDTO;
import com.linghu.entity.Brand;
import com.linghu.entity.User;
import com.linghu.entity.Warehouse;
import com.linghu.mapper.BrandMapper;
import com.linghu.mapper.UserMapper;
import com.linghu.mapper.WarehouseMapper;
import com.linghu.util.JwtUtil;
import com.linghu.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一认证控制器（指令3）
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserMapper userMapper;
    private final BrandMapper brandMapper;
    private final WarehouseMapper warehouseMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${wechat.miniapp.appid:wx_placeholder}")
    private String wxAppId;

    @Value("${wechat.miniapp.secret:placeholder_secret}")
    private String wxSecret;

    /**
     * 统一登录接口
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@Validated @RequestBody LoginDTO dto) {
        // 查询用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername())
                .eq(User::getDeleted, 0));

        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        // 验证密码
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 生成JWT
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("role", user.getRole());
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("avatar", user.getAvatar());

        // 根据角色返回额外信息
        if (user.getRole() == 1) {
            // 仓主：返回 warehouseId
            Warehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                    .eq(Warehouse::getUserId, user.getId())
                    .eq(Warehouse::getDeleted, 0)
                    .last("LIMIT 1"));
            if (warehouse != null) {
                result.put("warehouseId", warehouse.getId());
                result.put("warehouseName", warehouse.getName());
            }
        } else if (user.getRole() == 2) {
            // 品牌方：返回 brandId
            Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                    .eq(Brand::getUserId, user.getId())
                    .eq(Brand::getDeleted, 0)
                    .last("LIMIT 1"));
            if (brand != null) {
                result.put("brandId", brand.getId());
                result.put("companyName", brand.getCompanyName());
            }
        }

        log.info("用户登录成功: username={}, role={}", user.getUsername(), user.getRole());
        return R.ok("登录成功", result);
    }

    /**
     * 微信小程序登录
     * POST /api/auth/wx-miniapp
     * Body: { "code": "wx_login_code", "nickName": "昵称", "avatarUrl": "头像URL", "phone": "手机号(可选)" }
     * 用 openid 匹配已有用户，找不到则自动注册（role=0 消费者）
     */
    @PostMapping("/wx-miniapp")
    public R<Map<String, Object>> wxMiniappLogin(@RequestBody Map<String, Object> body) {
        String code = (String) body.get("code");
        if (code == null || code.isBlank()) throw new BusinessException("缺少 code 参数");

        // 1. 用 code 换 openid
        String url = String.format(
            "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
            wxAppId, wxSecret, code);
        String openid;
        try {
            RestTemplate rest = new RestTemplate();
            @SuppressWarnings("unchecked")
            Map<String, Object> wxResp = rest.getForObject(url, Map.class);
            if (wxResp == null || wxResp.containsKey("errcode")) {
                int errcode = wxResp != null ? (int) wxResp.getOrDefault("errcode", -1) : -1;
                String errmsg = wxResp != null ? (String) wxResp.getOrDefault("errmsg", "unknown") : "unknown";
                log.warn("微信 jscode2session 失败: errcode={}, errmsg={}", errcode, errmsg);
                throw new BusinessException("微信登录失败：" + errmsg);
            }
            openid = (String) wxResp.get("openid");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用微信接口异常", e);
            throw new BusinessException("微信登录接口异常，请稍后再试");
        }

        // 2. 按 openid 查找已有用户（openid 存在 username 字段前缀 wx_ 或 phone 字段）
        String nickName  = (String) body.getOrDefault("nickName",  "微信用户");
        String avatarUrl = (String) body.getOrDefault("avatarUrl", "");
        String phone     = (String) body.getOrDefault("phone",     null);
        String wxUsername = "wx_" + openid;

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, wxUsername)
                .eq(User::getDeleted, 0));

        if (user == null) {
            // 3. 自动注册新消费者
            user = new User();
            user.setUsername(wxUsername);
            user.setPassword(passwordEncoder.encode(openid)); // 密码无实际意义
            user.setPhone(phone);
            user.setAvatar(avatarUrl.isEmpty() ? null : avatarUrl);
            user.setRole(0);       // 消费者
            user.setStatus(1);
            user.setDeleted(0);
            // nickName 存入 username 展示字段（若 User 有 nickname 列则用 nickname）
            userMapper.insert(user);
            log.info("微信小程序新用户注册: openid={}, nickName={}", openid, nickName);
        } else {
            // 4. 更新头像和昵称
            boolean changed = false;
            if (!avatarUrl.isEmpty() && !avatarUrl.equals(user.getAvatar())) {
                user.setAvatar(avatarUrl);
                changed = true;
            }
            if (phone != null && !phone.equals(user.getPhone())) {
                user.setPhone(phone);
                changed = true;
            }
            if (changed) userMapper.updateById(user);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("role", user.getRole());
        result.put("username", nickName);
        result.put("avatar", user.getAvatar());
        result.put("isNewUser", user.getPhone() == null || user.getPhone().isBlank());

        log.info("微信小程序登录成功: userId={}, openid={}", user.getId(), openid);
        return R.ok("登录成功", result);
    }

    /**
     * 统一注册接口
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@Validated @RequestBody RegisterDTO dto) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername())
                .eq(User::getDeleted, 0));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        // 创建用户
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setRole(dto.getRole());
        user.setStatus(1);
        user.setDeleted(0);
        userMapper.insert(user);

        // 根据角色创建扩展信息
        if (dto.getRole() == 1) {
            // 仓主：创建空仓记录
            Warehouse warehouse = new Warehouse();
            warehouse.setUserId(user.getId());
            warehouse.setName(dto.getWarehouseName() != null ? dto.getWarehouseName() : dto.getUsername() + "的Mini仓");
            warehouse.setAddress(dto.getWarehouseAddress() != null ? dto.getWarehouseAddress() : "");
            warehouse.setCapacityVolume(1000000L);
            warehouse.setUsedVolume(0L);
            warehouse.setServiceFeeRate(new java.math.BigDecimal("2.00"));
            warehouse.setStatus(1);
            warehouse.setDeleted(0);
            warehouseMapper.insert(warehouse);
        } else if (dto.getRole() == 2) {
            // 品牌方：创建品牌记录
            Brand brand = new Brand();
            brand.setUserId(user.getId());
            brand.setCompanyName(dto.getCompanyName() != null ? dto.getCompanyName() : dto.getUsername() + "品牌");
            brand.setContactPerson(dto.getContactPerson());
            brand.setStatus(1);
            brand.setDeleted(0);
            brandMapper.insert(brand);
        }

        // 生成 JWT
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("role", user.getRole());
        result.put("userId", user.getId());

        return R.ok("注册成功", result);
    }

    /**
     * 获取当前用户信息
     * GET /api/auth/current-user
     */
    @GetMapping("/current-user")
    public R<Map<String, Object>> currentUser() {
        User currentUser = SecurityUtil.getCurrentUser();
        if (currentUser == null) {
            throw new BusinessException(401, "请先登录");
        }

        // 从数据库重新查询最新信息
        User user = userMapper.selectById(currentUser.getId());
        if (user == null || user.getDeleted() == 1) {
            throw new BusinessException(401, "用户不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("phone", user.getPhone());
        result.put("role", user.getRole());
        result.put("avatar", user.getAvatar());

        // 根据角色返回扩展信息
        if (user.getRole() == 1) {
            Warehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                    .eq(Warehouse::getUserId, user.getId())
                    .eq(Warehouse::getDeleted, 0)
                    .last("LIMIT 1"));
            if (warehouse != null) {
                result.put("warehouseId", warehouse.getId());
                result.put("warehouseName", warehouse.getName());
            }
        } else if (user.getRole() == 2) {
            Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                    .eq(Brand::getUserId, user.getId())
                    .eq(Brand::getDeleted, 0)
                    .last("LIMIT 1"));
            if (brand != null) {
                result.put("brandId", brand.getId());
                result.put("companyName", brand.getCompanyName());
            }
        }

        return R.ok(result);
    }
}
