package org.zjmproject;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.transaction.support.*;
import org.zjmproject.interceptors.*;
import org.zjmproject.observability.RuntimeEvents;
import org.zjmproject.service.AuditService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoggingTest {
    AuditService audit=mock(AuditService.class);
    ListAppender<ILoggingEvent> output;
    ch.qos.logback.classic.Logger logger;
    @BeforeEach void capture() {
        logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(RequestMetricsFilter.class);
        output=new ListAppender<>() { @Override protected void append(ILoggingEvent e) { e.prepareForDeferredProcessing();super.append(e); } };
        output.start();logger.addAppender(output);
    }
    @AfterEach void clear() { logger.detachAppender(output); MDC.clear(); }
    MockHttpServletRequest request(String method,String route) {
        var req=new MockHttpServletRequest(method,route);req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,route);return req;
    }
    @Test void businessFailureIsLoggedWithoutCapturingSecretsOrChangingResponse() throws Exception {
        var req=request("GET","/parking/selectParking");var res=new MockHttpServletResponse();
        req.addHeader("Authorization","secret-token");req.addParameter("password","secret-password");req.addHeader("X-Request-ID","correlation-123");
        new RequestMetricsFilter(audit,500,0,true).doFilter(req,res,(a,b)->{
            new ResponseOutcomeAdvice().beforeBodyWrite(org.zjmproject.pojo.Result.error("secret-error"),null,null,null,
                new org.springframework.http.server.ServletServerHttpRequest(req),new org.springframework.http.server.ServletServerHttpResponse(res));
        });
        assertEquals(200,res.getStatus());assertEquals("correlation-123",res.getHeader("X-Request-ID"));
        assertEquals(1,output.list.size());var event=output.list.get(0);
        assertTrue(event.getKeyValuePairs().toString().contains("FAILURE"));
        assertFalse(event.toString().contains("secret"));assertFalse(event.getKeyValuePairs().toString().contains("secret"));
        assertEquals("correlation-123",event.getMDCPropertyMap().get("requestId"));assertNull(MDC.get("requestId"));
    }
    @Test void samplingNeverDropsWritesOrFailuresButSuppressesHealthAndSuccessfulReads() throws Exception {
        var filter=new RequestMetricsFilter(audit,500,0,true);
        filter.doFilter(request("GET","/parking/list"),new MockHttpServletResponse(),(a,b)->{});
        filter.doFilter(request("GET","/actuator/health"),new MockHttpServletResponse(),(a,b)->{});
        assertTrue(output.list.isEmpty());
        filter.doFilter(request("POST","/rule/create"),new MockHttpServletResponse(),(a,b)->{});
        filter.doFilter(request("GET","/admin/audit/page"),new MockHttpServletResponse(),(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(403));
        assertEquals(2,output.list.size());
    }
    @Test void invalidIdsAreReplacedAndMdcIsRestored() throws Exception {
        assertNotEquals("bad\nID",RequestMetricsFilter.requestId("bad\nID"));
        assertNotEquals("x".repeat(65),RequestMetricsFilter.requestId("x".repeat(65)));
        MDC.put("requestId","parent");
        new RequestMetricsFilter(audit,500,0,true).doFilter(request("POST","/rule/create"),new MockHttpServletResponse(),(a,b)->{});
        assertEquals("parent",MDC.get("requestId"));
        try(var ignored=RuntimeEvents.scope("worker")) {assertEquals("worker",MDC.get("requestId"));}
        assertEquals("parent",MDC.get("requestId"));
    }
    @Test void asyncTimeoutAndCompletionProduceOneTerminalRequest() throws Exception {
        var req=request("GET","/ai/chat");req.setAsyncSupported(true);
        var res=new MockHttpServletResponse();
        new RequestMetricsFilter(audit,500,0,true).doFilter(req,res,(a,b)->req.startAsync(req,res));
        assertTrue(output.list.isEmpty());
        var context=(MockAsyncContext)req.getAsyncContext();
        for(var listener:context.getListeners())listener.onTimeout(new AsyncEvent(context));
        context.complete();
        assertEquals(1,output.list.size());assertTrue(output.list.get(0).getKeyValuePairs().toString().contains("TIMEOUT"));
        verify(audit,times(1)).requestFinished(req,"/ai/chat","TIMEOUT");
    }
    @Test void businessSuccessIsEmittedOnlyAfterCommit() {
        var business=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(RuntimeEvents.class);business.addAppender(output);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            RuntimeEvents.committed("reservation.create","SUCCESS",1);assertTrue(output.list.isEmpty());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertEquals(1,output.list.size());
        } finally { TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);business.detachAppender(output); }
    }
    @Test void loggingParametersAreBounded() {
        assertThrows(IllegalArgumentException.class,()->new RequestMetricsFilter(audit,500,Double.NaN,true));
        assertThrows(IllegalArgumentException.class,()->new RequestMetricsFilter(audit,0,0.1,true));
    }
    @Test void workerThreadsNeverInheritThePreviousRequest() throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            assertEquals("first",pool.submit(()->{try(var scope=RuntimeEvents.scope("first")){return MDC.get("requestId");}}).get());
            assertNull(pool.submit(()->MDC.get("requestId")).get());
            assertEquals("second",pool.submit(()->{try(var scope=RuntimeEvents.scope("second")){return MDC.get("requestId");}}).get());
            assertNull(pool.submit(()->MDC.get("requestId")).get());
        } finally {pool.shutdownNow();}
    }
    @Test void unexpectedExceptionRecordsOnlySafeMetadataOnce() {
        var target=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(org.zjmproject.exception.GlobalExceptionHandler.class);
        target.addAppender(output);
        try {
            var response=new org.zjmproject.exception.GlobalExceptionHandler().unexpected(new Exception("password=DO_NOT_LOG",new RuntimeException("token=DO_NOT_LOG")));
            assertEquals(500,response.getStatusCode().value());assertEquals(1,output.list.size());
            var event=output.list.get(0);assertFalse(event.getFormattedMessage().contains("DO_NOT_LOG"));assertNull(event.getThrowableProxy());
        } finally {target.detachAppender(output);}
    }
    @Test void independentAuditStorageFailureIsCountedAndDoesNotEscape() {
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(db.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret database credentials"));
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var service=new AuditService(db,new com.fasterxml.jackson.databind.ObjectMapper(),registry,manager,java.time.Clock.systemDefaultZone(),false);
        assertDoesNotThrow(()->service.bestEffort("admin.login",java.util.List.of(),null,null,"FAILURE","REQUEST_REJECTED"));
        assertEquals(1,registry.counter("parking.audit.record.failure").count());
    }
}
