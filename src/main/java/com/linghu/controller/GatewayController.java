package com.linghu.controller;

import com.linghu.annotation.RequireRole;
import com.linghu.common.R;
import com.linghu.util.SecurityUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 路由分发控制器（指令4）
 * 根据角色返回对应的首页配置
 */
@RestController
@RequestMapping("/api/home")
public class GatewayController {

    /**
     * 获取首页配置
     * GET /api/home/config
     */
    @GetMapping("/config")
    public R<Map<String, Object>> getHomeConfig() {
        Integer role = SecurityUtil.getCurrentRole();
        Map<String, Object> config = buildConfigByRole(role);
        return R.ok(config);
    }

    private Map<String, Object> buildConfigByRole(Integer role) {
        Map<String, Object> config = new HashMap<>();
        config.put("role", role);

        switch (role) {
            case 0: // 消费者
                config.put("homePage", "consumer");
                config.put("appTitle", "灵狐");
                config.put("themeColor", "#FF6B35");
                config.put("modules", Arrays.asList("productSearch", "cart", "orderList", "profile"));
                config.put("quickActions", Arrays.asList(
                        buildAction("scan", "扫码购", "scan_icon"),
                        buildAction("nearby", "附近仓", "location_icon"),
                        buildAction("coupon", "优惠券", "coupon_icon")
                ));
                config.put("tabBar", Arrays.asList(
                        buildTab("home", "首页", "home"),
                        buildTab("cart", "购物车", "shopping_cart"),
                        buildTab("orders", "订单", "receipt"),
                        buildTab("profile", "我的", "person")
                ));
                break;

            case 1: // 仓主
                config.put("homePage", "warehouse");
                config.put("appTitle", "灵狐掌柜");
                config.put("themeColor", "#2196F3");
                config.put("modules", Arrays.asList("workbench", "inventory", "earnings", "profile"));
                config.put("quickActions", Arrays.asList(
                        buildAction("inbound", "入库", "inbox_icon"),
                        buildAction("picking", "拣货", "local_shipping_icon"),
                        buildAction("inventory_check", "盘点", "inventory_icon")
                ));
                config.put("tabBar", Arrays.asList(
                        buildTab("workbench", "工作台", "work"),
                        buildTab("inventory", "库存", "inventory"),
                        buildTab("earnings", "收益", "attach_money"),
                        buildTab("profile", "我的", "person")
                ));
                break;

            case 2: // 品牌方
                config.put("homePage", "brand");
                config.put("appTitle", "灵狐品牌通");
                config.put("themeColor", "#4CAF50");
                config.put("modules", Arrays.asList("productManage", "replenishment", "orders", "dashboard"));
                config.put("quickActions", Arrays.asList(
                        buildAction("add_product", "上新商品", "add_box_icon"),
                        buildAction("replenish", "发起铺货", "send_icon"),
                        buildAction("data", "数据看板", "bar_chart_icon")
                ));
                config.put("tabBar", Arrays.asList(
                        buildTab("products", "商品", "inventory_2"),
                        buildTab("replenishment", "铺货", "local_shipping"),
                        buildTab("orders", "订单", "receipt_long"),
                        buildTab("dashboard", "数据", "bar_chart")
                ));
                break;

            default:
                config.put("homePage", "consumer");
                config.put("modules", Collections.emptyList());
        }

        return config;
    }

    private Map<String, Object> buildAction(String id, String label, String icon) {
        Map<String, Object> action = new HashMap<>();
        action.put("id", id);
        action.put("label", label);
        action.put("icon", icon);
        return action;
    }

    private Map<String, Object> buildTab(String id, String label, String icon) {
        Map<String, Object> tab = new HashMap<>();
        tab.put("id", id);
        tab.put("label", label);
        tab.put("icon", icon);
        return tab;
    }
}
