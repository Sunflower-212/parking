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
    @Autowired MembershipService membership;
    @Autowired UserService users;
    @Autowired DashboardService dashboard;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate sessions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PaymentQueue queue;
    @Autowired TestClock clock;
    @Autowired org.redisson.api.RedissonClient redis;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @MockitoBean AliPayService alipay;
    int user;
    String username;
    @BeforeEach void setup() throws Exception {
        // Drive delivery explicitly so advancing the test clock cannot race an immediate dispatch.
        doNothing().when(queue).scheduled(any());
        clock.instant=Instant.parse("2026-10-30T14:00:00Z");
        String name="pay"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        db.update("insert into user(user_name,password,balance,create_time,update_time) values(?,'test-only',20,now(),now())",name);
        user=db.queryForObject("select id from user where user_name=?",Integer.class,name);
        username=name;
        when(alipay.pay(anyInt())).thenAnswer(i->{int id=i.getArgument(0);return new FormContent<>("<form></form>","text/html",BigDecimal.TEN,id,"test");});
        when(alipay.query(anyString())).thenAnswer(i->new PaymentTrade(i.getArgument(0),null,"NOT_FOUND",null,null));
    }
    @AfterEach void cleanup() {sessions.delete("user="+username);db.update("delete from recharges where user_id=?",user);db.update("delete from membership_order where user_id=?",user);db.update("delete from user where id=?",user);}
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
        String project=System.getenv("PARKING_COMPOSE_PROJECT");
        assertTrue(Set.of("parking-payment-verification","parking-membership-verification").contains(project));
        int id=create();when(alipay.query(no(id))).thenReturn(paid(id));
        db.update("update payment_event set next_run_at=?,lease_token='crashed',lease_until=? where recharge_id=?",LocalDateTime.now(clock),LocalDateTime.now(clock).plusSeconds(1),id);
        long generation=db.queryForObject("select version from payment_event where recharge_id=?",Long.class,id);
        redis.<String>getDeque(PaymentQueue.PROCESSING).add(id+":"+generation);
        var order=member("MONTHLY","100");when(alipay.query(no(order.rechargeId()))).thenReturn(memberPaid(order));
        db.update("update payment_event set next_run_at=?,lease_token='crashed',lease_until=? where recharge_id=?",LocalDateTime.now(clock),LocalDateTime.now(clock).plusSeconds(1),order.rechargeId());
        redis.<String>getDeque(PaymentQueue.PROCESSING).add(order.rechargeId()+":1");
        clock.advance(2);
        var process=new ProcessBuilder("docker","restart",project+"-redis-1").redirectErrorStream(true).start();
        assertTrue(process.waitFor(45,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<until && !"PAID".equals(payments.status(user,id).paymentState())) Thread.sleep(100);
        assertEquals("PAID",payments.status(user,id).paymentState());
        while(System.nanoTime()<until && !"PAID".equals(payments.status(user,order.rechargeId()).paymentState())) Thread.sleep(100);
        assertEquals("SUCCEEDED",payments.status(user,order.rechargeId()).membershipOrder().state());
        balance("10");reserved("0");
    }

    MembershipService.Order member(String code, String balance) {
        db.update("update user set balance=? where id=?",new BigDecimal(balance),user);
        return membership.create(user,code,UUID.randomUUID().toString());
    }
    Map<String,String> memberNotification(MembershipService.Order order) {
        var params=new HashMap<>(notification(order.rechargeId()));
        params.put("total_amount",order.supplementAmount().toPlainString());
        return params;
    }
    PaymentTrade memberPaid(MembershipService.Order order) {
        return new PaymentTrade(no(order.rechargeId()),"trade-"+order.rechargeId(),"TRADE_SUCCESS",order.supplementAmount(),LocalDateTime.now(clock));
    }
    void reserved(String expected) {
        assertEquals(0,new BigDecimal(expected).compareTo(db.queryForObject("select reserved_balance from user where id=?",BigDecimal.class,user)));
    }
    @Test void membershipPlansChargeExactPricesAndReplayWithoutRenewingTwice() {
        db.update("update user set balance=5000 where id=?",user);
        LocalDateTime end=LocalDateTime.now(clock);
        for(var plan:membership.plans()) {
            String key=UUID.randomUUID().toString();
            var order=membership.create(user,plan.code(),key);
            end=end.plusMonths(plan.months());
            assertEquals("SUCCEEDED",order.state());assertEquals(end,order.endsAt());
            assertEquals(order.id(),membership.create(user,plan.code(),key).id());
            String other=plan.code().equals("MONTHLY")?"YEARLY":"MONTHLY";
            assertThrows(IllegalArgumentException.class,()->membership.create(user,other,key));
        }
        balance("2436");reserved("0");
        assertEquals(3,db.queryForObject("select count(*) from recharges where user_id=? and business_type='MEMBERSHIP_DEBIT' and flag=1 and pay_method is null",Integer.class,user));
        assertThrows(IllegalArgumentException.class,()->membership.create(user,"FREE",UUID.randomUUID().toString()));
    }
    @Test void membershipUsesCalendarMonthsAndRestartsExpiredTermsAtSettlement() {
        clock.instant=Instant.parse("2026-01-31T02:00:00Z");
        var month=member("MONTHLY","3000");
        assertEquals(LocalDateTime.of(2026,2,28,10,0),month.endsAt());
        clock.instant=Instant.parse("2026-03-31T02:00:00Z");
        var quarter=membership.create(user,"QUARTERLY",UUID.randomUUID().toString());
        assertFalse(quarter.renewed());assertEquals(LocalDateTime.of(2026,6,30,10,0),quarter.endsAt());
        db.update("update user set vip_start_time=null,vip_end_time=null,status=0 where id=?",user);
        clock.instant=Instant.parse("2024-02-29T02:00:00Z");
        var year=membership.create(user,"YEARLY",UUID.randomUUID().toString());
        assertEquals(LocalDateTime.of(2025,2,28,10,0),year.endsAt());
    }
    @Test void membershipReservesBalanceAndSettlesSupplementAndDebitOnce() {
        var order=member("MONTHLY","100.01");
        balance("100.01");reserved("100.01");
        assertEquals(new BigDecimal("87.99"),order.supplementAmount());
        assertEquals(order.id(),membership.current(user).id());
        assertThrows(IllegalStateException.class,()->users.deductions(user,BigDecimal.ONE));
        payments.confirm(memberNotification(order));
        var end=db.queryForObject("select vip_end_time from user where id=?",LocalDateTime.class,user);
        payments.confirm(memberNotification(order));payments.check(user,order.rechargeId());
        balance("0");reserved("0");assertNull(membership.current(user));
        assertEquals(end,db.queryForObject("select vip_end_time from user where id=?",LocalDateTime.class,user));
        assertEquals("SUCCEEDED",payments.status(user,order.rechargeId()).membershipOrder().state());
        assertEquals(2,db.queryForObject("select count(*) from recharges where membership_order_id=?",Integer.class,order.id()));
    }
    @Test void membershipZeroBalanceAndConcurrentPurchaseReserveOnlyOnce() throws Exception {
        db.update("update user set balance=0 where id=?",user);
        String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->membership.create(user,"YEARLY",key));
            var second=pool.submit(()->membership.create(user,"YEARLY",key));
            var order=first.get(10,TimeUnit.SECONDS);
            assertEquals(order.id(),second.get(10,TimeUnit.SECONDS).id());
            assertEquals(new BigDecimal("1888.00"),order.supplementAmount());reserved("0");
            assertThrows(IllegalStateException.class,()->membership.create(user,"MONTHLY",UUID.randomUUID().toString()));
        } finally {pool.shutdownNow();}
        assertEquals(1,db.queryForObject("select count(*) from membership_order where user_id=?",Integer.class,user));
    }
    @Test void membershipCloseReleasesReservationAndLatePaymentOnlyCreditsWallet() {
        var order=member("MONTHLY","100");
        payments.cancel(user,order.rechargeId());reserved("100");
        clock.advance(1801);assertEquals("CLOSED",payments.check(user,order.rechargeId()).paymentState());
        reserved("0");balance("100");users.deductions(user,new BigDecimal("100"));
        payments.confirm(memberNotification(order));payments.confirm(memberNotification(order));
        balance("88");reserved("0");
        assertEquals(0,db.queryForObject("select status from user where id=?",Integer.class,user));
        assertEquals("CLOSED",payments.status(user,order.rechargeId()).membershipOrder().state());
        assertEquals(0,db.queryForObject("select count(*) from recharges where membership_order_id=? and business_type='MEMBERSHIP_DEBIT'",Integer.class,order.id()));
    }
    @Test void membershipCloseNotificationReleasesFundsAndPaymentWinningCancelStillActivates() throws Exception {
        var closed=member("MONTHLY","100");
        var notice=new HashMap<>(memberNotification(closed));notice.put("trade_status","TRADE_CLOSED");
        payments.confirm(notice);reserved("0");payments.confirm(notice);balance("100");
        var winner=membership.create(user,"MONTHLY",UUID.randomUUID().toString());
        when(alipay.query(no(winner.rechargeId()))).thenReturn(new PaymentTrade(no(winner.rechargeId()),null,"WAIT_BUYER_PAY",winner.supplementAmount(),null),memberPaid(winner));
        when(alipay.close(no(winner.rechargeId()))).thenReturn(false);
        assertEquals("SUCCEEDED",payments.cancel(user,winner.rechargeId()).membershipOrder().state());
        reserved("0");balance("0");
    }
    @Test void membershipProviderFailureAndFormFailurePreserveOrderAndReservation() throws Exception {
        var order=member("MONTHLY","100");
        when(alipay.pay(order.rechargeId())).thenThrow(new IllegalStateException("test form failure"));
        assertThrows(IllegalStateException.class,()->membership.pay(user,order.id()));
        assertEquals(order.id(),membership.current(user).id());reserved("100");
        when(alipay.query(no(order.rechargeId()))).thenReturn(new PaymentTrade(no(order.rechargeId()),null,"UNKNOWN",null,null));
        assertEquals("REVIEW",payments.check(user,order.rechargeId()).paymentState());
        assertNull(db.queryForObject("select completed_at from payment_event where recharge_id=?",Object.class,order.rechargeId()));
        assertThrows(IllegalArgumentException.class,()->membership.pay(user+99999,order.id()));
        assertThrows(IllegalArgumentException.class,()->payments.status(user+99999,order.rechargeId()));
        balance("100");reserved("100");
    }
    @Test void membershipWrongAmountAndDatabaseFailureCannotPartiallyCreditOrActivate() {
        var order=member("MONTHLY","100");
        var wrong=new HashMap<>(memberNotification(order));wrong.put("total_amount","1.00");
        assertThrows(IllegalArgumentException.class,()->payments.confirm(wrong));balance("100");reserved("100");
        db.update("insert into recharges(user_id,amounts,flag,create_time,business_type,membership_order_id) values(?,188,1,?,'MEMBERSHIP_DEBIT',?)",user,LocalDateTime.now(clock),order.id());
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->payments.confirm(memberNotification(order)));
            balance("100");reserved("100");
            assertEquals("PENDING",membership.current(user).state());
            assertEquals(0,db.queryForObject("select status from user where id=?",Integer.class,user));
            assertEquals(0,db.queryForObject("select flag from recharges where id=?",Integer.class,order.rechargeId()));
        } finally {db.update("delete from recharges where membership_order_id=? and business_type='MEMBERSHIP_DEBIT'",order.id());}
        payments.confirm(memberNotification(order));balance("0");reserved("0");
    }
    @Test void membershipConcurrentNotificationQueryAndInterruptedDeliveryAreIdempotent() throws Exception {
        var order=member("MONTHLY","100");
        when(alipay.query(no(order.rechargeId()))).thenReturn(memberPaid(order));
        var pool=Executors.newFixedThreadPool(3);
        try {
            var a=pool.submit(()->payments.check(user,order.rechargeId()));
            var b=pool.submit(()->payments.confirm(memberNotification(order)));
            var c=pool.submit(()->payments.confirm(memberNotification(order)));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);c.get(10,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        balance("0");reserved("0");
        assertEquals(2,db.queryForObject("select count(*) from recharges where membership_order_id=?",Integer.class,order.id()));
        var next=member("MONTHLY","100");when(alipay.query(no(next.rechargeId()))).thenReturn(memberPaid(next));
        db.update("update payment_event set lease_token='interrupted',lease_until=?,next_run_at=? where recharge_id=?",LocalDateTime.now(clock).plusSeconds(1),LocalDateTime.now(clock).plusSeconds(1),next.rechargeId());
        clock.advance(2);queue.deliver(next.rechargeId());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline && !"PAID".equals(payments.status(user,next.rechargeId()).paymentState())) Thread.sleep(50);
        assertEquals("PAID",payments.status(user,next.rechargeId()).paymentState());balance("0");reserved("0");
    }

    @Test @SuppressWarnings("unchecked") void membershipHttpUsesAuthenticatedOwnerAndServerPricesAndRetiresFreeEndpoints() {
        assertEquals(401,http.getForEntity("/user/membership/plans",String.class).getStatusCode().value());
        String token=org.zjmproject.utils.JwtUtil.genToken(Map.of("id",user,"username",username,"permissions",-1));
        sessions.opsForValue().set("user="+username,token);
        var headers=new org.springframework.http.HttpHeaders();headers.set("Authorization",token);
        headers.set("Idempotency-Key",UUID.randomUUID().toString());
        var entity=new org.springframework.http.HttpEntity<>(Map.of("planCode","MONTHLY","price",1,"userId",user+99999),headers);
        var response=http.postForEntity("/user/membership/orders",entity,Map.class).getBody();
        assertEquals(0,response.get("code"));var order=(Map<String,Object>)response.get("data");
        assertEquals(0,new BigDecimal(order.get("price").toString()).compareTo(new BigDecimal("188")));
        var info=http.exchange("/user/userInfo",org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),Map.class).getBody();
        var account=(Map<String,Object>)info.get("data");assertEquals(0,new BigDecimal(account.get("available_balance").toString()).signum());
        for(String path:List.of("/user/getVip","/user/vipRenewal")) {
            var retired=http.exchange(path,org.springframework.http.HttpMethod.PATCH,new org.springframework.http.HttpEntity<>(headers),Map.class).getBody();
            assertEquals(1,retired.get("code"));assertEquals("请刷新页面选择会员套餐",retired.get("message"));
        }
        assertEquals(0,db.queryForObject("select status from user where id=?",Integer.class,user));
    }
    @Test @SuppressWarnings("unchecked") void membershipDebitIsExcludedFromExternalRechargeStatistics() {
        java.util.function.Supplier<Map<String,Object>> today=()-> {
            var series=(List<Map<String,Object>>)dashboard.summary(1,1,null,1).get("series");return series.get(0);
        };
        var before=today.get();
        member("MONTHLY","500");
        var afterDebit=today.get();
        assertEquals(new BigDecimal(before.get("rechargeAmount").toString()),new BigDecimal(afterDebit.get("rechargeAmount").toString()));
        var supplement=member("MONTHLY","100");payments.confirm(memberNotification(supplement));
        var after=today.get();
        assertEquals(new BigDecimal("88.00"),new BigDecimal(after.get("rechargeAmount").toString()).subtract(new BigDecimal(before.get("rechargeAmount").toString())));
        assertEquals(1,((Number)after.get("rechargeCount")).longValue()-((Number)before.get("rechargeCount")).longValue());
    }
}
