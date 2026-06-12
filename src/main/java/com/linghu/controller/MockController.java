package com.linghu.controller;

import com.linghu.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Mock 角色切换控制器（指令13）
 * 无需JWT验证，用于前端开发阶段调试角色切换效果
 */
@RestController
@RequestMapping("/api/mock")
public class MockController {

    /**
     * 模拟角色切换
     * GET /api/mock/switch-role?role=0/1/2
     */
    @GetMapping("/switch-role")
    public R<Map<String, Object>> switchRole(@RequestParam(defaultValue = "0") Integer role) {
        Map<String, Object> config = buildMockConfig(role);
        return R.ok(config);
    }

    /**
     * 获取Mock登录Token（仅开发用）
     * GET /api/mock/login?role=0/1/2
     */
    @GetMapping("/login")
    public R<Map<String, Object>> mockLogin(@RequestParam(defaultValue = "0") Integer role) {
        Map<String, Object> result = new HashMap<>();
        // 模拟的 mock token（不可用于真实接口）
        result.put("mockToken", "mock_token_role_" + role + "_" + System.currentTimeMillis());
        result.put("role", role);
        result.put("userId", role + 1L);
        result.put("username", getRoleName(role));
        result.put("note", "此为Mock Token，仅用于UI调试，不可调用真实API");
        result.put("config", buildMockConfig(role));
        return R.ok(result);
    }

    private Map<String, Object> buildMockConfig(Integer role) {
        Map<String, Object> config = new HashMap<>();
        config.put("role", role);

        switch (role) {
            case 0:
                config.put("homePage", "consumer");
                config.put("appTitle", "灵狐");
                config.put("themeColor", "#FF6B35");
                config.put("modules", Arrays.asList("productSearch", "cart", "orderList", "profile"));
                config.put("tabBar", Arrays.asList(
                        buildTab("home", "首页", "home"),
                        buildTab("cart", "购物车", "shopping_cart"),
                        buildTab("orders", "订单", "receipt"),
                        buildTab("profile", "我的", "person")
                ));
                config.put("mockProducts", getMockProducts());
                break;

            case 1:
                config.put("homePage", "warehouse");
                config.put("appTitle", "灵狐掌柜");
                config.put("themeColor", "#2196F3");
                config.put("modules", Arrays.asList("workbench", "inventory", "earnings", "profile"));
                config.put("tabBar", Arrays.asList(
                        buildTab("workbench", "工作台", "work"),
                        buildTab("inventory", "库存", "inventory"),
                        buildTab("earnings", "收益", "attach_money"),
                        buildTab("profile", "我的", "person")
                ));
                config.put("mockStats", getMockWarehouseStats());
                break;

            case 2:
                config.put("homePage", "brand");
                config.put("appTitle", "灵狐品牌通");
                config.put("themeColor", "#4CAF50");
                config.put("modules", Arrays.asList("productManage", "replenishment", "orders", "dashboard"));
                config.put("tabBar", Arrays.asList(
                        buildTab("products", "商品", "inventory_2"),
                        buildTab("replenishment", "铺货", "local_shipping"),
                        buildTab("orders", "订单", "receipt_long"),
                        buildTab("dashboard", "数据", "bar_chart")
                ));
                config.put("mockStats", getMockBrandStats());
                break;
        }

        return config;
    }

    private List<Map<String, Object>> getMockProducts() {
        List<Map<String, Object>> products = new ArrayList<>();
        String[] names = {"灵狐有机牛奶250ml", "灵狐坚果混合装200g", "灵狐矿泉水500ml"};
        double[] prices = {5.90, 29.90, 2.50};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> p = new HashMap<>();
            p.put("productId", i + 1);
            p.put("name", names[i]);
            p.put("price", prices[i]);
            p.put("image", "https://picsum.photos/seed/" + i + "/300/300");
            p.put("distance", (i + 1) + ".2km");
            p.put("stock", 50 - i * 10);
            products.add(p);
        }
        return products;
    }

    private Map<String, Object> getMockWarehouseStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingInbound", 3);
        stats.put("pendingPicking", 5);
        stats.put("totalInventoryItems", 8);
        stats.put("todayEarnings", 35.00);
        return stats;
    }

    private Map<String, Object> getMockBrandStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProducts", 5);
        stats.put("totalOrders", 128);
        stats.put("totalInventory", 980);
        stats.put("monthSales", 12800.00);
        return stats;
    }

    private Map<String, Object> buildTab(String id, String label, String icon) {
        Map<String, Object> tab = new HashMap<>();
        tab.put("id", id);
        tab.put("label", label);
        tab.put("icon", icon);
        return tab;
    }

    private String getRoleName(Integer role) {
        switch (role) {
            case 0: return "consumer";
            case 1: return "warehouse";
            case 2: return "brand";
            default: return "unknown";
        }
    }
}
