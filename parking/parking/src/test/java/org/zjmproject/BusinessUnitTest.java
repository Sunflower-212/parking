package org.zjmproject;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.zjmproject.interceptors.AuthenticationInterceptor;
import org.zjmproject.service.PaymentService;
import org.zjmproject.utils.JwtUtil;
import org.zjmproject.utils.Md5Util;
import org.zjmproject.utils.MoneysUtil;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessUnitTest {
    @Test void chargeKeepsFractionalPricesAndUnitBoundaries() {
        assertEquals(new BigDecimal("0.00"),MoneysUtil.getMoneys(10,10,30,new BigDecimal("2.50")));
        assertEquals(new BigDecimal("2.50"),MoneysUtil.getMoneys(11,10,30,new BigDecimal("2.50")));
        assertEquals(new BigDecimal("2.50"),MoneysUtil.getMoneys(40,10,30,new BigDecimal("2.50")));
        assertEquals(new BigDecimal("5.00"),MoneysUtil.getMoneys(41,10,30,new BigDecimal("2.50")));
        assertThrows(IllegalArgumentException.class,()->MoneysUtil.getMoneys(20,0,0,BigDecimal.ONE));
    }
    @Test void oldAndNewPasswordsRemainCompatible() {
        String hash=Md5Util.encode("test-password");
        assertTrue(Md5Util.checkPassword("test-password",hash));
        assertFalse(Md5Util.checkPassword("incorrect",hash));
        assertTrue(Md5Util.checkPassword("test-password",Md5Util.getMD5String("test-password")));
    }
    @Test @SuppressWarnings("unchecked") void oldTokenIsRejectedEvenIfAnotherTokenExists() throws Exception {
        new JwtUtil("unit-test-only-secret-is-at-least-32-characters");
        String token=JwtUtil.genToken(Map.of("id",1,"username","tester","permissions",-1));
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.get("user=tester")).thenReturn("different-token");
        var req=new MockHttpServletRequest("GET","/user/userInfo");req.addHeader("Authorization",token);
        var response=new MockHttpServletResponse();
        assertFalse(new AuthenticationInterceptor(redis).preHandle(req,response,new Object()));
        assertEquals(401,response.getStatus());
    }
    @Test void disabledPaymentCannotCreateACharge() {
        PaymentService payment=new PaymentService(null,null,null,false,"");
        assertThrows(IllegalStateException.class,()->payment.create(1,BigDecimal.TEN,0));
    }
}
