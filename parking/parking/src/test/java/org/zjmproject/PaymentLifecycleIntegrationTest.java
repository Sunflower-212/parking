package org.zjmproject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.zjmproject.service.*;
import org.zjmproject.pojo.FormContent;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="PARKING_INTEGRATION_TESTS",matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
    "spring.data.redis.port=${TEST_REDIS_PORT:26480}","parking.integrations.alipay=true","alipay.app-id=integration-app"
})
class PaymentLifecycleIntegrationTest {
    static class TestClock extends Clock {
        volatile Instant instant=Instant.parse("2026-10-30T14:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant,zone);}
        public Instant instant(){return instant;}
        void advance(long seconds){instant=instant.plusSeconds(seconds);}
    }
    @TestConfiguration static class Time {
        @Bean @Primary TestClock paymentClock(){return new TestClock();}
        @Bean(destroyMethod="shutdown") @Primary org.redisson.api.RedissonClient paymentTestRedis(
                @org.springframework.beans.factory.annotation.Value("${TEST_REDIS_PORT}") int port,
                @org.springframework.beans.factory.annotation.Value("${spring.data.redis.password}") String password) {
            // Other cached Spring test contexts have live consumers; give this provider stub its own DB.
            var config=new org.redisson.config.Config();config.setThreads(2).setNettyThreads(2);
            config.setCodec(org.redisson.client.codec.StringCodec.INSTANCE);
            config.useSingleServer().setAddress("redis://127.0.0.1:"+port).setPassword(password).setDatabase(7)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4).setSubscriptionConnectionMinimumIdleSize(1);
            return org.redisson.Redisson.create(config);
        }
    }
    @Autowired JdbcTemplate db;
    @Autowired PaymentService payments;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PaymentQueue queue;
    @Autowired TestClock clock;
    @Autowired org.redisson.api.RedissonClient redis;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @MockitoBean AliPayService alipay;
    int user;
    @BeforeEach void setup() throws Exception {
        // Drive delivery explicitly so advancing the test clock cannot race an immediate dispatch.
        doNothing().when(queue).scheduled(any());
        clock.instant=Instant.parse("2026-10-30T14:00:00Z");
        String name="pay"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        db.update("insert into user(user_name,password,balance,create_time,update_time) values(?,'test-only',20,now(),now())",name);
        user=db.queryForObject("select id from user where user_name=?",Integer.class,name);
        when(alipay.pay(anyInt())).thenAnswer(i->{int id=i.getArgument(0);return new FormContent<>("<form></form>","text/html",BigDecimal.TEN,id,"test");});
        when(alipay.query(anyString())).thenAnswer(i->new PaymentTrade(i.getArgument(0),null,"NOT_FOUND",null,null));
    }
    @AfterEach void cleanup() {db.update("delete from recharges where user_id=?",user);db.update("delete from user where id=?",user);}
    int create() throws Exception {return payments.create(user,BigDecimal.TEN,0).getRechargeId();}
    String no(int id) {return db.queryForObject("select merchant_order_no from recharges where id=?",String.class,id);}
    PaymentTrade paid(int id) {return new PaymentTrade(no(id),"trade-"+id,"TRADE_SUCCESS",BigDecimal.TEN,LocalDateTime.now(clock));}
    void balance(String expected) {assertEquals(0,new BigDecimal(expected).compareTo(db.queryForObject("select balance from user where id=?",BigDecimal.class,user)));}
    Map<String,String> notification(int id) {
        return Map.of("app_id","integration-app","out_trade_no",no(id),"trade_status","TRADE_SUCCESS","total_amount","10.00","trade_no","trade-"+id,"gmt_payment",LocalDateTime.now(clock).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
    @Test void lostNotificationIsRecoveredByAQueryAndRequestsAreThrottled() throws Exception {
        int id=create();when(alipay.query(no(id))).thenReturn(paid(id));
        assertEquals("PAID",payments.check(user,id).paymentState());
        payments.check(user,id);payments.confirm(notification(id));balance("30");
        verify(alipay,times(1)).query(no(id));
        assertNotNull(db.queryForObject("select completed_at from payment_event where recharge_id=?",Object.class,id));
    }
    @Test void newDeadlineAndNoEarlyExpiryUseTheSameThirtyMinutes() throws Exception {
        int id=create();var created=db.queryForObject("select create_time from recharges where id=?",LocalDateTime.class,id);
        assertEquals(created.plusMinutes(30),payments.status(user,id).expiresAt());
        payments.check(user,id);assertEquals("PENDING",payments.status(user,id).paymentState());
        payments.check(user,id);verify(alipay,times(1)).query(no(id));
        clock.advance(1800);payments.check(user,id);
        assertEquals("CLOSED",payments.status(user,id).paymentState());balance("20");
    }
    @Test void cancellationCannotClaimThatAnUncreatedProviderTradeWasClosed() throws Exception {
        int id=create();assertEquals("CLOSING",payments.cancel(user,id).paymentState());
        assertEquals(0,db.queryForObject("select flag from recharges where id=?",Integer.class,id));
        clock.advance(11);when(alipay.query(no(id))).thenReturn(paid(id));payments.check(user,id);
        balance("30");assertEquals("PAID",payments.status(user,id).paymentState());
    }
    @Test void paymentWinningAClosingRaceIsCredited() throws Exception {
        int id=create();clock.advance(1801);
        when(alipay.query(no(id))).thenReturn(new PaymentTrade(no(id),null,"WAIT_BUYER_PAY",BigDecimal.TEN,null),paid(id));
        when(alipay.close(no(id))).thenReturn(false);
        assertEquals("PAID",payments.check(user,id).paymentState());balance("30");
    }
    @Test void expiredUnpaidTradeIsClosedOnlyAfterProviderConfirmation() throws Exception {
        int id=create();clock.advance(1800);
        when(alipay.query(no(id))).thenReturn(new PaymentTrade(no(id),null,"WAIT_BUYER_PAY",BigDecimal.TEN,null));
        when(alipay.close(no(id))).thenReturn(true);
        assertEquals("CLOSED",payments.check(user,id).paymentState());balance("20");
    }
    @Test void providerFailureRemainsUnconfirmedAndKeepsTheDurableTask() throws Exception {
        int id=create();clock.advance(1800);when(alipay.query(no(id))).thenThrow(new com.alipay.api.AlipayApiException("test-only"));
        assertEquals("VERIFYING",payments.check(user,id).paymentState());
        assertEquals(0,db.queryForObject("select flag from recharges where id=?",Integer.class,id));
        assertNull(db.queryForObject("select completed_at from payment_event where recharge_id=?",Object.class,id));balance("20");
    }
    @Test void amountMismatchDoesNotCreditAndUniqueTradeFailureRollsBackBalance() throws Exception {
        int a=create(),b=create();
        when(alipay.query(no(a))).thenReturn(new PaymentTrade(no(a),"bad","TRADE_SUCCESS",BigDecimal.ONE,LocalDateTime.now(clock)));
        assertEquals("REVIEW",payments.check(user,a).paymentState());balance("20");
        when(alipay.query(no(a))).thenReturn(paid(a));clock.advance(11);payments.check(user,a);balance("30");
        when(alipay.query(no(b))).thenReturn(new PaymentTrade(no(b),"trade-"+a,"TRADE_SUCCESS",BigDecimal.TEN,LocalDateTime.now(clock)));
        payments.check(user,b);balance("30");assertNotEquals("PAID",payments.status(user,b).paymentState());
    }
    @Test void concurrentNotificationAndQueryCannotDoubleCreditOrLeakOwnership() throws Exception {
        int id=create();assertThrows(IllegalArgumentException.class,()->payments.check(user+99999,id));
        assertThrows(IllegalArgumentException.class,()->payments.status(user+99999,id));
        when(alipay.query(no(id))).thenReturn(paid(id));var pool=Executors.newFixedThreadPool(3);
        try {var a=pool.submit(()->payments.check(user,id));var b=pool.submit(()->payments.confirm(notification(id)));var c=pool.submit(()->payments.confirm(notification(id)));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);c.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        balance("30");assertEquals("PAID",payments.status(user,id).paymentState());
    }
    @Test void staleGenerationAndDuplicateQueueCopiesDoNotTriggerAnotherQuery() throws Exception {
        int id=create();long version=db.queryForObject("select version from payment_event where recharge_id=?",Long.class,id);
        when(alipay.query(no(id))).thenReturn(paid(id));payments.check(user,id);
        redis.<String>getBlockingDeque(PaymentQueue.READY).add(id+":"+version);
        redis.<String>getBlockingDeque(PaymentQueue.READY).add(id+":"+version);
        Thread.sleep(300);verify(alipay,times(1)).query(no(id));balance("30");
    }
    @Test void persistedIntentIsRedeliveredAndAProcessingLeaseRecovers() throws Exception {
        int id=create();when(alipay.query(no(id))).thenReturn(paid(id));
        db.update("update payment_event set lease_token='interrupted',lease_until=?,next_run_at=? where recharge_id=?",LocalDateTime.now(clock).plusSeconds(1),LocalDateTime.now(clock).plusSeconds(1),id);
        clock.advance(2);queue.deliver(id);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until && !"PAID".equals(payments.status(user,id).paymentState())) Thread.sleep(50);
        assertEquals("PAID",payments.status(user,id).paymentState());balance("30");
    }
    @Test void legacyCancelledRecordsCanBeRecoveredWithoutInventingNewDeadlines() throws Exception {
        db.update("insert into recharges(user_id,amounts,pay_method,create_time,flag) values(?,10,0,?,2)",user,LocalDateTime.now(clock).minusMinutes(10));
        int id=db.queryForObject("select max(id) from recharges where user_id=?",Integer.class,user);
        when(alipay.query(Integer.toString(id))).thenReturn(new PaymentTrade(Integer.toString(id),"legacy-"+id,"TRADE_SUCCESS",BigDecimal.TEN,LocalDateTime.now(clock)));
        payments.recoverLegacy();assertEquals("PAID",payments.check(user,id).paymentState());balance("30");
        assertNull(payments.status(user,id).expiresAt());
    }
    @Test void notificationProtocolIsPlainTextAndOnlyAcknowledgesCommittedCredit() throws Exception {
        int id=create();
        var failure=http.postForEntity("/user/rechargeNotify",null,String.class);
        assertEquals("failure",failure.getBody());assertTrue(failure.getHeaders().getContentType().toString().startsWith("text/plain"));balance("20");
        when(alipay.rechargePayNotify(any())).thenReturn(notification(id));
        assertEquals("success",http.postForEntity("/user/rechargeNotify",null,String.class).getBody());balance("30");
        assertEquals("success",http.postForEntity("/user/rechargeNotify",null,String.class).getBody());balance("30");
        assertEquals(401,http.getForEntity("/user/recharge/"+id+"/status",String.class).getStatusCode().value());
    }
    @Test @EnabledIfEnvironmentVariable(named="PARKING_PAYMENT_RECOVERY_TESTS",matches="true")
    void redisRestartRestoresAnInterruptedPayment() throws Exception {
        assertEquals("parking-payment-verification",System.getenv("PARKING_COMPOSE_PROJECT"));
        int id=create();when(alipay.query(no(id))).thenReturn(paid(id));
        db.update("update payment_event set next_run_at=?,lease_token='crashed',lease_until=? where recharge_id=?",LocalDateTime.now(clock),LocalDateTime.now(clock).plusSeconds(1),id);
        long generation=db.queryForObject("select version from payment_event where recharge_id=?",Long.class,id);
        redis.<String>getDeque(PaymentQueue.PROCESSING).add(id+":"+generation);
        clock.advance(2);
        var process=new ProcessBuilder("docker","restart","parking-payment-verification-redis-1").redirectErrorStream(true).start();
        assertTrue(process.waitFor(45,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<until && !"PAID".equals(payments.status(user,id).paymentState())) Thread.sleep(100);
        assertEquals("PAID",payments.status(user,id).paymentState());balance("30");
    }
}
