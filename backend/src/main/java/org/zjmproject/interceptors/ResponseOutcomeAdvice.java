package org.zjmproject.interceptors;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.zjmproject.pojo.Result;

/** Observe the business code without buffering bodies or changing the API contract. */
@RestControllerAdvice
public class ResponseOutcomeAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter p, Class<? extends HttpMessageConverter<?>> c) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter p, MediaType type,
            Class<? extends HttpMessageConverter<?>> c, ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet) {
            if (body instanceof Result<?> result)
                servlet.getServletRequest().setAttribute("log.businessFailed", !Integer.valueOf(0).equals(result.getCode()));
            else if (p.getMethod() != null && p.getMethod().getName().equals("rechargeNotify"))
                servlet.getServletRequest().setAttribute("log.businessFailed", !"success".equals(body));
        }
        return body;
    }
}
