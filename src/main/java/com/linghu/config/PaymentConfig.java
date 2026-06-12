package com.linghu.config;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第三方支付客户端配置
 *
 * 支付宝：设置 alipay.enabled=true 并填写真实商户参数后启用
 * 微信支付：设置 wechat.pay.enabled=true 并填写真实商户参数后启用
 *
 * 未配置时 Bean 不注册，系统自动退回「模拟充值/提现」流程。
 */
@Slf4j
@Configuration
public class PaymentConfig {

    // ==================== 支付宝 ====================

    @Value("${alipay.app-id:}")
    private String alipayAppId;

    @Value("${alipay.private-key:}")
    private String alipayPrivateKey;

    @Value("${alipay.public-key:}")
    private String alipayPublicKey;

    @Value("${alipay.gateway:https://openapi.alipay.com/gateway.do}")
    private String alipayGateway;

    /**
     * 支付宝客户端 Bean，仅当 alipay.enabled=true 时注册
     */
    @Bean
    @ConditionalOnProperty(name = "alipay.enabled", havingValue = "true")
    public AlipayClient alipayClient() {
        log.info("[支付宝] 正在初始化 AlipayClient, appId={}", alipayAppId);
        return new DefaultAlipayClient(
                alipayGateway,
                alipayAppId,
                alipayPrivateKey,
                "json",
                "UTF-8",
                alipayPublicKey,
                "RSA2"
        );
    }

    // ==================== 微信支付 ====================

    @Value("${wechat.pay.app-id:}")
    private String wxAppId;

    @Value("${wechat.pay.mch-id:}")
    private String wxMchId;

    @Value("${wechat.pay.api-v3-key:}")
    private String wxApiV3Key;

    @Value("${wechat.pay.cert-serial-no:}")
    private String wxCertSerialNo;

    @Value("${wechat.pay.private-key-path:classpath:wechat/apiclient_key.pem}")
    private String wxPrivateKeyPath;

    /**
     * 微信支付 Service Bean，仅当 wechat.pay.enabled=true 时注册
     */
    @Bean
    @ConditionalOnProperty(name = "wechat.pay.enabled", havingValue = "true")
    public WxPayService wxPayService() {
        log.info("[微信支付] 正在初始化 WxPayService, appId={}, mchId={}", wxAppId, wxMchId);
        WxPayConfig config = new WxPayConfig();
        config.setAppId(wxAppId);
        config.setMchId(wxMchId);
        config.setApiV3Key(wxApiV3Key);
        config.setCertSerialNo(wxCertSerialNo);
        config.setPrivateKeyPath(wxPrivateKeyPath);
        // 使用 APIv3 签名体系
        config.setSignType("HMAC-SHA256");
        WxPayService service = new WxPayServiceImpl();
        service.setConfig(config);
        return service;
    }
}
