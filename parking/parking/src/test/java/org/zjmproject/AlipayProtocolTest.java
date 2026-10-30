package org.zjmproject;

import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.zjmproject.config.AliPayConfig;
import org.zjmproject.mapper.RechargesMapper;
import org.zjmproject.pojo.Recharges;
import org.zjmproject.service.impl.AliPayServiceImpl;
import java.security.KeyPairGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AlipayProtocolTest {
    @Test void verifiesRsaNotificationsAndRejectsTamperingOrDuplicateParameters() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var config=new AliPayConfig();config.setAlipayPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var service=new AliPayServiceImpl();ReflectionTestUtils.setField(service,"config",config);
        var params=new HashMap<String,String>();params.put("out_trade_no","7");params.put("trade_status","TRADE_SUCCESS");params.put("total_amount","10.00");params.put("sign_type","RSA2");
        var toSign=new HashMap<>(params);toSign.remove("sign_type");
        params.put("sign",AlipaySignature.rsaSign(AlipaySignature.getSignContent(toSign),Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),"UTF-8","RSA2"));
        var request=new MockHttpServletRequest();params.forEach(request::setParameter);
        assertNotNull(service.rechargePayNotify(request));
        request.setParameter("total_amount","999.00");assertNull(service.rechargePayNotify(request));
        request.setParameter("total_amount",new String[]{"10.00","999.00"});assertNull(service.rechargePayNotify(request));
    }
    @Test void generatedFormUsesPersistedAbsoluteDeadlineMerchantNumberAndReturnContext() throws Exception {
        var client=mock(AlipayClient.class);var mapper=mock(RechargesMapper.class);
        var service=new AliPayServiceImpl(){@Override protected AlipayClient client(){return client;}};
        var config=new AliPayConfig();config.setReturnUrl("https://example.invalid/parking/user/paymentResult");config.setNotifyUrl("https://example.invalid/parking/api/user/rechargeNotify");
        ReflectionTestUtils.setField(service,"config",config);ReflectionTestUtils.setField(service,"recharges",mapper);
        var row=new Recharges();row.setId(7);row.setFlag(0);row.setPayment_state("PENDING");row.setAmounts(BigDecimal.TEN);row.setMerchant_order_no("PKunique");row.setExpires_at(LocalDateTime.of(2026,10,30,23,0));
        when(mapper.findRechargesById(7)).thenReturn(row);
        var response=new AlipayTradePagePayResponse();response.setBody("<form></form>");when(client.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(response);
        service.pay(7);var captured=ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);verify(client).pageExecute(captured.capture());
        var body=new org.json.JSONObject(captured.getValue().getBizContent());
        assertEquals("PKunique",body.getString("out_trade_no"));assertEquals("2026-10-30 23:00:00",body.getString("time_expire"));
        assertFalse(body.has("timeout_express"));assertEquals(config.getReturnUrl()+"?rechargeId=7",captured.getValue().getReturnUrl());
    }
    @Test void notFoundAndCloseRaceAreExplicitProviderResults() throws Exception {
        var client=mock(AlipayClient.class);var service=new AliPayServiceImpl(){@Override protected AlipayClient client(){return client;}};
        var query=new AlipayTradeQueryResponse();query.setCode("40004");query.setSubCode("ACQ.TRADE_NOT_EXIST");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(query);
        assertEquals("NOT_FOUND",service.query("7").status());
        var close=new AlipayTradeCloseResponse();close.setCode("40004");close.setSubCode("ACQ.TRADE_HAS_SUCCESS");when(client.execute(any(AlipayTradeCloseRequest.class))).thenReturn(close);
        assertFalse(service.close("7"));
        query.setSubCode("ACQ.SYSTEM_ERROR");assertThrows(com.alipay.api.AlipayApiException.class,()->service.query("7"));
    }
    @Test void realSdkSignsAPageFormLocallyWithoutASandboxPayment() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var config=new AliPayConfig();config.setAppId("1234567890123456");
        config.setAppPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        config.setAlipayPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        config.setNotifyUrl("https://example.invalid/parking/api/user/rechargeNotify");config.setReturnUrl("https://example.invalid/parking/user/paymentResult");
        var mapper=mock(RechargesMapper.class);var row=new Recharges();row.setId(7);row.setFlag(0);row.setPayment_state("PENDING");row.setAmounts(BigDecimal.TEN);row.setMerchant_order_no("PKoffline");row.setExpires_at(LocalDateTime.of(2026,10,30,23,30));
        when(mapper.findRechargesById(7)).thenReturn(row);
        var service=new AliPayServiceImpl();ReflectionTestUtils.setField(service,"config",config);ReflectionTestUtils.setField(service,"recharges",mapper);
        String form=service.pay(7).getForm();
        assertTrue(form.contains("PKoffline"));assertTrue(form.contains("time_expire"));assertTrue(form.contains("sign="));
    }
}
