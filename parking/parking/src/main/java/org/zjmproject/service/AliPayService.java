package org.zjmproject.service;

import com.alipay.api.AlipayApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONException;
import org.zjmproject.pojo.FormContent;
import org.zjmproject.pojo.Result;

import java.io.IOException;
import java.util.Map;

public interface AliPayService {

    FormContent<String> pay(Integer rechargesId) throws JSONException;

    Map<String, String> rechargePayNotify(HttpServletRequest request) throws AlipayApiException;
}
