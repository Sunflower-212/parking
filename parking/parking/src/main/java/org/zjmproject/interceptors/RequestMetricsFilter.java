package org.zjmproject.interceptors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.UUID;
@Component public class RequestMetricsFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String incoming=req.getHeader("X-Request-ID");String id=incoming!=null && incoming.matches("[a-zA-Z0-9-]{1,64}")?incoming:UUID.randomUUID().toString();
        res.setHeader("X-Request-ID",id);MDC.put("requestId",id);
        try{chain.doFilter(req,res);}finally{MDC.remove("requestId");}
    }
}
