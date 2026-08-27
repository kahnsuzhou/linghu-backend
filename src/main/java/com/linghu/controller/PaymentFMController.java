package com.linghu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.entity.PaymentOrder;
import com.linghu.entity.Wallet;
import com.linghu.mapper.PaymentOrderMapper;
import com.linghu.mapper.WalletMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payment/fm")
public class PaymentFMController {

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletController walletController;

    @PostMapping("/notify")
    public Map<String, String> notify(HttpServletRequest request) {
        Map<String, String> response = new HashMap<>();
        try {
            // 1. 获取支付FM通知参数
            Map<String, String> params = new HashMap<>();
            request.getParameterMap().forEach((key, values) -> {
                params.put(key, values[0]);
            });

            // 2. 根据订单号查询支付订单
            String orderNo = params.get("orderNo");
            if (orderNo == null) {
                response.put("code", "FAIL");
                response.put("msg", "缺少订单号");
                return response;
            }

            PaymentOrder po = paymentOrderMapper.selectOne(
                new LambdaQueryWrapper<PaymentOrder>()
                    .eq(PaymentOrder::getOrderNo, orderNo)
            );
            if (po == null) {
                response.put("code", "FAIL");
                response.put("msg", "订单不存在");
                return response;
            }

            // 3. 如果已支付，直接返回成功
            if ("SUCCESS".equals(po.getStatus())) {
                response.put("code", "200");
                response.put("msg", "success");
                return response;
            }

            // 4. 更新支付订单状态
            po.setStatus("SUCCESS");
            po.setChannelOrderNo(params.get("tradeNo"));
            paymentOrderMapper.updateById(po);

            // 5. 如果有关联的订单ID，自动完成订单支付
            if (po.getRefId() != null && po.getRefId() > 0) {
                // 先充值到钱包
                Long userId = po.getUserId();
                Wallet wallet = walletMapper.selectOne(
                    new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId)
                );
                if (wallet != null) {
                    wallet.setBalance(wallet.getBalance().add(po.getAmount()));
                    walletMapper.updateById(wallet);
                }
                // 再完成订单支付（直接调用，传入订单ID）
            }

            response.put("code", "200");
            response.put("msg", "success");
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", "FAIL");
            response.put("msg", e.getMessage());
            return response;
        }
    }
}
