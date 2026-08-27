package com.linghu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "payment.fm")
public class PaymentFMService {

    private String apiUrl;
    private String merchantId;
    private String merchantKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setMerchantKey(String merchantKey) { this.merchantKey = merchantKey; }

    public String createOrder(String outTradeNo, String totalAmount, String subject, String notifyUrl) {
        System.out.println("=== PaymentFMService.createOrder 开始 ===");
        System.out.println("totalAmount: " + totalAmount);
        System.out.println("merchantId: " + merchantId);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("merchantNum", merchantId);
            params.put("outTradeNo", outTradeNo);
            params.put("totalAmount", totalAmount);
            params.put("money", totalAmount);
            params.put("subject", subject);
            params.put("notify_url", notifyUrl);
            params.put("payType", "ALIPAY");
            params.put("sign", generateSign(params));

            String requestUrl = apiUrl + "/startOrder";
            String jsonBody = objectMapper.writeValueAsString(params);
        System.out.println("=== 请求体: " + jsonBody);

            HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("支付FM接口返回错误码: " + responseCode);
            }

            java.io.InputStream inputStream = connection.getInputStream();
            byte[] responseBytes = inputStream.readAllBytes();
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            if (responseMap.containsKey("payUrl")) {
                return (String) responseMap.get("payUrl");
            } else {
                throw new RuntimeException("支付FM返回数据异常: " + responseBody);
            }

        } catch (Exception e) {
            throw new RuntimeException("支付FM创建订单失败: " + e.getMessage(), e);
        }
    }

    private String generateSign(Map<String, Object> params) {
        String signStr = merchantKey + params.get("outTradeNo") + params.get("totalAmount") + params.get("payType");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(signStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("生成签名失败", e);
        }
    }
}
