package org.zjmproject.interceptors;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.zjmproject.observability.RuntimeEvents;
import org.zjmproject.service.AuditService;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final AuditService audit;
    private final long slowMs;
    private final double sample;
    private final boolean enabled;
    public RequestMetricsFilter(AuditService audit,
            @Value("${parking.logging.slow-request-ms:500}") long slowMs,
            @Value("${parking.logging.read-sample:0.1}") double sample,
            @Value("${parking.logging.requests-enabled:true}") boolean enabled) {
        if (slowMs < 1 || !Double.isFinite(sample) || sample < 0 || sample > 1) throw new IllegalArgumentException("Invalid logging settings");
        this.audit=audit; this.slowMs=slowMs; this.sample=sample; this.enabled=enabled;
    }
    public static String requestId(String incoming) {
        return incoming != null && incoming.matches("[a-zA-Z0-9-]{1,64}") ? incoming : UUID.randomUUID().toString();
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String id=requestId(req.getHeader("X-Request-ID"));
        res.setHeader("X-Request-ID",id); req.setAttribute("log.requestId",id);
        long start=System.nanoTime(); AtomicBoolean finished=new AtomicBoolean();
        Runnable finish=()->{
            if (!finished.compareAndSet(false,true)) return;
            try (var ignored=RuntimeEvents.scope(id)) {
                long elapsed=(System.nanoTime()-start)/1_000_000;
                Object matched=req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // 不记录原始URL和查询参数；未匹配路径也可能携带攻击者提供的敏感内容。
                String route=matched==null?"UNMATCHED":matched.toString();
                String terminal=(String)req.getAttribute("log.terminal");
                boolean failed=res.getStatus()>=400 || Boolean.TRUE.equals(req.getAttribute("log.businessFailed")) || terminal!=null;
                String result=failed?(terminal==null?"FAILURE":terminal):"SUCCESS";
                audit.requestFinished(req,route,result);
                boolean write=!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD");
                if (enabled && (failed || !route.equals("/actuator/health") && (write || elapsed>=slowMs || ThreadLocalRandom.current().nextDouble()<sample))) {
                    var logger=LoggerFactory.getLogger(RequestMetricsFilter.class);
                    var event=res.getStatus()>=500?logger.atError():failed || elapsed>=slowMs?logger.atWarn():logger.atInfo();
                    event.addKeyValue("event","http.request").addKeyValue("method",req.getMethod())
                        .addKeyValue("route",route).addKeyValue("status",res.getStatus()).addKeyValue("result",result)
                        .addKeyValue("elapsedMs",elapsed).addKeyValue("actorId",req.getAttribute("log.actorId"))
                        .addKeyValue("actorRole",req.getAttribute("log.actorRole")).log("request_finished");
                }
            }
        };
        try (var ignored=RuntimeEvents.scope(id)) {
            try { chain.doFilter(req,res); }
            catch (IOException | ServletException | RuntimeException e) { req.setAttribute("log.terminal","ERROR"); throw e; }
            finally {
                if (!req.isAsyncStarted()) finish.run();
                else {
                    AsyncListener listener=new AsyncListener() {
                        public void onComplete(AsyncEvent e) { finish.run(); }
                        public void onTimeout(AsyncEvent e) { req.setAttribute("log.terminal","TIMEOUT"); finish.run(); }
                        public void onError(AsyncEvent e) { req.setAttribute("log.terminal","ERROR"); finish.run(); }
                        public void onStartAsync(AsyncEvent e) { e.getAsyncContext().addListener(this); }
                    };
                    try { req.getAsyncContext().addListener(listener); }
                    catch (IllegalStateException alreadyCompleted) { finish.run(); }
                }
            }
        }
    }
}
