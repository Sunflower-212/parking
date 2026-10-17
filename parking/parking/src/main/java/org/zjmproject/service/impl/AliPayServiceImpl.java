package org.zjmproject.service.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.config.AliPayConfig;
import org.zjmproject.mapper.RechargesMapper;
import org.zjmproject.pojo.FormContent;
import org.zjmproject.pojo.Recharges;
import org.zjmproject.service.AliPayService;

import java.util.HashMap;
import java.util.Map;


@Service
public class AliPayServiceImpl implements AliPayService {

    private static final String GATEWAY_URL = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String FORMAT = "JSON";
    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";

    @Autowired
    private AliPayConfig aliPayConfig;

    @Autowired
    private RechargesMapper rechargesMapper;


    @Override
    public FormContent<String> pay(Integer rechargesId) throws JSONException{
        Recharges recharges = rechargesMapper.findRechargesById(rechargesId);
        if(recharges.getFlag()!=0){
            throw new RuntimeException("Recharge is not available.");
        }
        //1创建Client,通过SDK提供的Client，负责调用支付宝的API
        AlipayClient alipayClient = new DefaultAlipayClient(GATEWAY_URL,aliPayConfig.getAppId(),
                aliPayConfig.getAppPrivateKey(),FORMAT,CHARSET, aliPayConfig.getAlipayPublicKey(),SIGN_TYPE);
        //创建Request并设置Request参数
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();//发送请求的Request类
        request.setNotifyUrl(aliPayConfig.getNotifyUrl());
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no",recharges.getId());//充值id
        bizContent.put("total_amount",recharges.getAmounts());//金额
        bizContent.put("subject","余额充值");//支付名称
        bizContent.put("product_code","FAST_INSTANT_TRADE_PAY");//固定配置

        request.setBizContent(bizContent.toString());
        request.setNotifyUrl(aliPayConfig.getNotifyUrl());//设置回调接口
        request.setReturnUrl(aliPayConfig.getReturnUrl());//设置支付后跳转的页面


        //执行请求，拿到响应数据，返回给浏览器
        String form = "";
        try{
            form = alipayClient.pageExecute(request).getBody();
            // 将表单内容和内容类型封装到FormContent对象中
            FormContent<String> formContent = new FormContent<>(form, "text/html;charset=" + CHARSET,recharges.getAmounts(),recharges.getId(),"余额充值");
            return formContent;
        } catch (AlipayApiException e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    public Map<String, String> rechargePayNotify(HttpServletRequest request) throws AlipayApiException {
        if(!java.util.Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(request.getParameter("trade_status")==null?"":request.getParameter("trade_status"))) return null;
        Map<String,String> params=new HashMap<>();
        request.getParameterMap().forEach((name,values)->{if(values.length!=1) throw new IllegalArgumentException("重复的支付参数");params.put(name,values[0]);});
        if(aliPayConfig.getAlipayPublicKey()==null || aliPayConfig.getAlipayPublicKey().isBlank()) return null;
        return AlipaySignature.rsaCheckV1(params,aliPayConfig.getAlipayPublicKey(),CHARSET,SIGN_TYPE)?params:null;
    }
}
