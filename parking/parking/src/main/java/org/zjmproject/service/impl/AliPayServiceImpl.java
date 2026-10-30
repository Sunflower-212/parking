package org.zjmproject.service.impl;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.config.AliPayConfig;
import org.zjmproject.mapper.RechargesMapper;
import org.zjmproject.pojo.FormContent;
import org.zjmproject.service.AliPayService;
import org.zjmproject.service.PaymentTrade;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AliPayServiceImpl implements AliPayService {
    private static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    @Autowired private AliPayConfig config;
    @Autowired private RechargesMapper recharges;
    protected AlipayClient client() {
        var client=new DefaultAlipayClient(GATEWAY,config.getAppId(),config.getAppPrivateKey(),
                "JSON","UTF-8",config.getAlipayPublicKey(),"RSA2");
        client.setConnectTimeout(3000); client.setReadTimeout(5000);
        return client;
    }
    @Override public FormContent<String> pay(Integer id) {
        var charge=recharges.findRechargesById(id);
        if(charge==null || charge.getFlag()!=0 || !"PENDING".equals(charge.getPayment_state()))
            throw new IllegalStateException("充值单不可支付，请重新创建");
        var content=new JSONObject();
        content.put("out_trade_no",charge.getMerchant_order_no());
        content.put("total_amount",charge.getAmounts());
        content.put("subject","余额充值"); content.put("product_code","FAST_INSTANT_TRADE_PAY");
        content.put("time_expire",charge.getExpires_at().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        var request=new AlipayTradePagePayRequest(); request.setBizContent(content.toString());
        request.setNotifyUrl(config.getNotifyUrl());
        // The internal ID is navigation context only; ownership and payment facts are checked server-side.
        request.setReturnUrl(config.getReturnUrl()+(config.getReturnUrl().contains("?")?"&":"?")+"rechargeId="+id);
        try {
            var result=new FormContent<>(client().pageExecute(request).getBody(),"text/html;charset=UTF-8",charge.getAmounts(),id,"余额充值");
            result.setMerchantOrderNo(charge.getMerchant_order_no()); result.setExpiresAt(charge.getExpires_at());
            return result;
        } catch(AlipayApiException e) { throw new IllegalStateException("生成支付页面失败，请稍后重试"); }
    }
    @Override public Map<String,String> rechargePayNotify(HttpServletRequest request) throws AlipayApiException {
        Map<String,String> params=new HashMap<>();
        for(var entry:request.getParameterMap().entrySet()) {
            if(entry.getValue().length!=1) return null;
            params.put(entry.getKey(),entry.getValue()[0]);
        }
        if(config.getAlipayPublicKey()==null || config.getAlipayPublicKey().isBlank()) return null;
        return AlipaySignature.rsaCheckV1(params,config.getAlipayPublicKey(),"UTF-8","RSA2")?params:null;
    }
    @Override public PaymentTrade query(String orderNo) throws AlipayApiException {
        var request=new AlipayTradeQueryRequest();
        request.setBizContent(new JSONObject().put("out_trade_no",orderNo).toString());
        var result=client().execute(request);
        if(!result.isSuccess()) {
            if("ACQ.TRADE_NOT_EXIST".equals(result.getSubCode())) return new PaymentTrade(orderNo,null,"NOT_FOUND",null,null);
            throw new AlipayApiException("PAYMENT_QUERY_FAILED");
        }
        return new PaymentTrade(result.getOutTradeNo(),result.getTradeNo(),result.getTradeStatus(),
                result.getTotalAmount()==null?null:new BigDecimal(result.getTotalAmount()),
                result.getSendPayDate()==null?null:LocalDateTime.ofInstant(result.getSendPayDate().toInstant(),ZoneId.of("Asia/Shanghai")));
    }
    @Override public boolean close(String orderNo) throws AlipayApiException {
        var request=new AlipayTradeCloseRequest();
        request.setBizContent(new JSONObject().put("out_trade_no",orderNo).toString());
        var result=client().execute(request);
        if(result.isSuccess()) return true;
        if(Set.of("ACQ.TRADE_NOT_EXIST","ACQ.TRADE_HAS_SUCCESS","ACQ.TRADE_STATUS_ERROR").contains(Objects.toString(result.getSubCode(),""))) return false;
        throw new AlipayApiException("PAYMENT_CLOSE_FAILED");
    }
}
