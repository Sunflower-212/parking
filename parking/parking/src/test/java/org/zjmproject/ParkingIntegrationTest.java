package org.zjmproject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.zjmproject.service.*;
import org.zjmproject.utils.Md5Util;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PARKING_INTEGRATION_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
    "spring.data.redis.port=${TEST_REDIS_PORT:6380}","parking.reservation-timeout=2s",
    "parking.integrations.alipay=true","alipay.app-id=integration-app",
    "parking.cache.enabled=true","parking.cache.port=${TEST_CACHE_PORT:26381}","parking.cache.password=${CACHE_REDIS_PASSWORD}"
})
class ParkingIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ParkingFlowService flow;
    @Autowired ReservationQueue queue;
    @Autowired PaymentService payments;
    @Autowired OrdersService orders;
    @Autowired TestRestTemplate http;
    @Autowired Clock clock;
    int user,user2,park,park2,space,space2,rule;
    String prefix,plate,plate2;
    int insert(String sql,Object...args) {
        var keys=new GeneratedKeyHolder();
        db.update(c->{var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]);return p;},keys);
        return Objects.requireNonNull(keys.getKey()).intValue();
    }
    @BeforeEach void fixture() {
        prefix="it"+UUID.randomUUID().toString().replace("-","").substring(0,8);
        plate="京A-"+prefix.substring(2,7).toUpperCase();plate2="京B-"+prefix.substring(2,7).toUpperCase();
        user=insert("insert into user(user_name,password,plate_num,balance,create_time,update_time) values(?,?,?,20,now(),now())",prefix,Md5Util.getMD5String("test12345"),plate);
        user2=insert("insert into user(user_name,password,plate_num,balance,create_time,update_time) values(?,?,?,20,now(),now())",prefix+"b",Md5Util.encode("test12345"),plate2);
        int admin=db.queryForObject("select id from admin where permissions=1 limit 1",Integer.class);
        rule=insert("insert into rule(rule_name,free_time,charging_unit,unit_cost,create_time,update_time) values(?,10,30,2.50,now(),now())",prefix);
        park=insert("insert into parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) values(?,?,?,2,2,1,?,now(),now())",admin,prefix,"test",rule);
        park2=insert("insert into parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) values(?,?,?,0,0,1,?,now(),now())",admin,prefix+"b","test",rule);
        space=insert("insert into spaces(park_id,spaces_name,spaces_col,spaces_row,spaces_storey,spaces_flag,create_time,update_time) values(?,'A1',1,1,1,0,now(),now())",park);
        space2=insert("insert into spaces(park_id,spaces_name,spaces_col,spaces_row,spaces_storey,spaces_flag,create_time,update_time) values(?,'A2',2,1,1,0,now(),now())",park);
    }
    @AfterEach void cleanup() {
        db.update("delete from recharges where user_id in (?,?)",user,user2);
        db.update("delete from orders where user_id in (?,?)",user,user2);
        db.update("delete from reservation_event where reservation_id in (select id from enter where user_id in (?,?))",user,user2);
        db.update("delete from enter where user_id in (?,?)",user,user2);
        db.update("delete from spaces where park_id in (?,?)",park,park2);
        db.update("delete from parking where id in (?,?)",park,park2);
        db.update("delete from rule where id=?",rule);
        db.update("delete from user where id in (?,?)",user,user2);
    }
    int available() {return db.queryForObject("select park_spare from parking where id=?",Integer.class,park);}
    @Test void reservationEntryAndExitMaintainCapacityAndChargeExactlyOnce() {
        var reservation=flow.reserve(user,space);assertEquals(1,available());
        flow.enter(plate,park);assertEquals(1,available());
        assertThrows(IllegalStateException.class,()->flow.cancel(user,reservation.getId()));
        flow.expire(reservation.getId());assertEquals(1,available());
        db.update("update enter set enter_time=? where id=?",LocalDateTime.now(clock).minusMinutes(70),reservation.getId());
        flow.exit(plate,park);assertEquals(2,available());
        assertEquals(0,new BigDecimal("15.00").compareTo(db.queryForObject("select balance from user where id=?",BigDecimal.class,user)));
        assertThrows(IllegalArgumentException.class,()->flow.exit(plate,park));
        assertEquals(1,db.queryForObject("select count(*) from orders where user_id=?",Integer.class,user));
    }
    @Test void oldExpiryCannotReleaseANewReservation() {
        int old=flow.reserve(user,space).getId();flow.cancel(user,old);
        int newer=flow.reserve(user,space2).getId();flow.expire(old);
        assertEquals(newer,db.queryForObject("select active_enter_id from spaces where id=?",Integer.class,space2));
        assertEquals(1,available());
    }
    @Test void duplicateAndForeignCancellationAreRejected() {
        var r=flow.reserve(user,space);
        assertThrows(IllegalStateException.class,()->flow.reserve(user,space2));
        assertThrows(IllegalArgumentException.class,()->flow.cancel(user2,r.getId()));
        assertThrows(IllegalArgumentException.class,()->flow.cancel(user,2147483647));
        assertThrows(IllegalStateException.class,()->flow.enter(plate,park2));
    }
    @Test void twoUsersCannotReserveTheSameSpace() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int uid:List.of(user,user2)) attempts.add(pool.submit(()->{start.await();try{flow.reserve(uid,space);return true;}catch(RuntimeException e){return false;}}));
            start.countDown();int successes=0;for(var attempt:attempts) if(attempt.get(10,TimeUnit.SECONDS)) successes++;
            assertEquals(1,successes);assertEquals(1,available());
        } finally {pool.shutdownNow();}
    }
    @Test void realRedisQueueExpiresReservationsAndRecoveryIsIdempotent() throws Exception {
        int id=flow.reserve(user,space).getId();
        Thread.sleep(200);
        assertEquals(0,db.queryForObject("select attempts from reservation_event where reservation_id=?",Integer.class,id));
        queue.reconnected();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline && db.queryForObject("select reservation from enter where id=?",Integer.class,id)==1) Thread.sleep(100);
        assertEquals(2,db.queryForObject("select reservation from enter where id=?",Integer.class,id));
        flow.expire(id);assertEquals(2,available());
        assertNotNull(db.queryForObject("select completed_at from reservation_event where reservation_id=?",LocalDateTime.class,id));
    }
    @Test void expiryIsCheckedAgainstTheDatabaseDeadline() {
        int id=flow.reserve(user,space).getId();assertThrows(IllegalStateException.class,()->flow.expire(id));
        db.update("update enter set expires_at=? where id=?",LocalDateTime.now(clock).minusSeconds(1),id);
        flow.expire(id);assertEquals(2,available());
    }
    @Test void processingMessagesAreRecoveredAfterAnInterruptedConsumer() throws Exception {
        int id=flow.reserve(user,space).getId();
        db.update("update enter set expires_at=? where id=?",LocalDateTime.now(clock).minusSeconds(1),id);
        // Redis still contains the message after a consumer dies between claim and commit.
        org.redisson.config.Config config=new org.redisson.config.Config();
        config.setCodec(org.redisson.client.codec.StringCodec.INSTANCE);
        config.useSingleServer().setAddress("redis://127.0.0.1:"+System.getenv().getOrDefault("TEST_REDIS_PORT","6380")).setPassword(System.getenv("REDIS_PASSWORD")).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        var client=org.redisson.Redisson.create(config);
        try {
            client.<String>getDeque("parking:{reservation}:processing").add(Integer.toString(id));
            queue.reconnected();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<deadline && available()!=2) Thread.sleep(100);
            assertEquals(2,available());
        } finally {client.shutdown();}
    }
    @Test void administratorOrderQueriesCannotReadOtherParkingLots() {
        int otherAdmin=insert("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",prefix+"a",Md5Util.encode("test12345"));
        try {
            db.update("update parking set admin_id=? where id=?",otherAdmin,park2);
            for(int parkId:List.of(park,park2)) db.update("insert into orders(plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time) values(?,?,?,now(),now(),0,0,now())",plate,user,parkId);
            assertEquals(1,orders.adminGetPageList(1,10,null,null,null,otherAdmin).getTotal());
            assertEquals(1,orders.rootGetPageList(1,10,null,null,null,prefix+"a").getTotal());
        } finally {
            db.update("update parking set admin_id=(select min(id) from admin where permissions=1) where id=?",park2);
            db.update("delete from admin where id=?",otherAdmin);
        }
    }
    @Test void failedExitRollsBackAllState() {
        flow.enter(plate,park);db.update("update user set balance=0 where id=?",user);db.update("update enter set enter_time=? where user_id=?",LocalDateTime.now(clock).minusHours(2),user);
        assertThrows(IllegalStateException.class,()->flow.exit(plate,park));
        assertEquals(1,available());assertEquals(1,db.queryForObject("select flag from enter where user_id=?",Integer.class,user));
        assertThrows(IllegalStateException.class,()->flow.exit(plate,park2));
    }
    @Test void lateAndRepeatedPaymentCreditsOnlyOnce() {
        int id=insert("insert into recharges(user_id,amounts,flag,pay_method,create_time) values(?,12.34,2,0,now())",user);
        var params=new HashMap<String,String>();params.put("app_id","integration-app");params.put("trade_status","TRADE_SUCCESS");params.put("out_trade_no",Integer.toString(id));params.put("total_amount","12.34");params.put("trade_no",prefix);params.put("gmt_payment","2026-10-17 12:00:00");
        payments.confirm(params);payments.confirm(params);
        assertEquals(0,new BigDecimal("32.34").compareTo(db.queryForObject("select balance from user where id=?",BigDecimal.class,user)));
        params.put("total_amount","99.00");assertThrows(IllegalArgumentException.class,()->payments.confirm(params));
    }
    @Test void loginUpgradesMd5AndRevokesTheOldToken() {
        var form=new LinkedMultiValueMap<String,String>();form.add("username",prefix);form.add("password","test12345");
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var first=http.postForEntity("/user/login",new HttpEntity<>(form,headers),Map.class);
        assertEquals(0,first.getBody().get("code"));String old=(String)first.getBody().get("data");
        assertTrue(db.queryForObject("select password from user where id=?",String.class,user).startsWith("$2"));
        http.postForEntity("/user/login",new HttpEntity<>(form,headers),Map.class);
        headers.set("Authorization",old);
        assertEquals(401,http.exchange("/user/userInfo",HttpMethod.GET,new HttpEntity<>(headers),String.class).getStatusCode().value());
        assertEquals(401,http.getForEntity("/enter/adminPageList?pageNum=1&pageSize=5",String.class).getStatusCode().value());
    }

    @Autowired DashboardService dashboard;
    @Autowired MembershipService membership;
    @Autowired ReadCache cache;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate tokens;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String token(int id,String name,int role) {
        var claims=new HashMap<String,Object>();claims.put("id",id);claims.put("permissions",role);claims.put(role==-1?"username":"adminname",name);
        String value=org.zjmproject.utils.JwtUtil.genToken(claims);
        tokens.opsForValue().set((role==-1?"user=":"admin=")+name,value,java.time.Duration.ofMinutes(5));return value;
    }
    ResponseEntity<Map> get(String path,String token) {var h=new HttpHeaders();h.set("Authorization",token);return http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),Map.class);}
    @Test void platformManagementIsSuperAdministratorOnly() {
        String ordinary=token(999999,prefix+"admin",0),normal=token(user,prefix,-1);
        for(String path:List.of("/user/pageList?pageNum=1&pageSize=20","/user/export","/recharges/pageList?pageNum=1&pageSize=20","/recharges/data","/admin/list")) assertEquals(403,get(path,ordinary).getStatusCode().value(),path);
        assertEquals(403,get("/dashboard/summary",normal).getStatusCode().value());
        assertEquals(0,get("/system/capabilities",normal).getBody().get("code"));
    }
    @Test void paginatedHistoryIsStableAndLegacyHistoryIsBounded() {
        for(int i=0;i<105;i++)db.update("insert into orders(plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time) values(?,?,?,now(),now(),0,0,now())",plate,user,park);
        String auth=token(user,prefix,-1);
        Map body=get("/orders/userPageList?pageNum=1&pageSize=20",auth).getBody();assertEquals(0,body.get("code"));
        Map data=(Map)body.get("data");assertEquals(105,((Number)data.get("total")).intValue());assertEquals(20,((List)data.get("items")).size());
        List<Map> items=(List<Map>)data.get("items");assertTrue(((Number)items.get(0).get("id")).intValue()>((Number)items.get(1).get("id")).intValue());
        assertEquals(1,get("/orders/userList",auth).getBody().get("code"));
        assertEquals(1,get("/orders/userPageList?pageNum=1&pageSize=101",auth).getBody().get("code"));
        assertEquals(0,get("/recharges/userPageList",auth).getBody().get("code"));
    }
    @Test void dashboardScopesInSqlAndNeverReturnsPlatformMetricsToOrdinaryAdmin() {
        int admin=insert("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",prefix+"scope",Md5Util.encode("test12345"));
        try {
            db.update("update parking set admin_id=? where id=?",admin,park2);
            db.update("insert into orders(plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time) values(?,?,?,now(),now(),0,9,now())",plate,user,park);
            String auth=token(admin,prefix+"scope",0);
            Map data=(Map)get("/dashboard/summary",auth).getBody().get("data");Map counts=(Map)data.get("counters");
            assertEquals(1,((Number)counts.get("parkingNumber")).intValue());assertFalse(counts.containsKey("userNumber"));assertEquals(0,new BigDecimal(counts.get("revenueToday").toString()).signum());
            assertEquals(1,get("/dashboard/summary?park_id="+park,auth).getBody().get("code"));
            assertEquals(1,get("/spaces/layout?park_id="+park+"&spaces_storey=1",auth).getBody().get("code"));
            assertEquals(1,get("/dashboard/summary?days=32",auth).getBody().get("code"));
        } finally {db.update("update parking set admin_id=(select min(id) from admin where permissions=1) where id=?",park2);db.update("delete from admin where id=?",admin);}
    }
    @Test void layoutAndAvailabilityNeverDiscloseTheOccupyingUser() {
        flow.reserve(user,space);String auth=token(user2,prefix+"b",-1);
        Map response=get("/spaces/layout?park_id="+park+"&spaces_storey=1",auth).getBody();
        List<Map> rows=(List<Map>)response.get("data");assertFalse(rows.isEmpty());assertTrue(rows.stream().noneMatch(r->r.containsKey("user_name")||r.containsKey("active_enter_id")));
        List<Map> states=(List<Map>)get("/spaces/availability?park_id="+park+"&spaces_storey=1",auth).getBody().get("data");assertEquals(1,((Number)states.stream().filter(r->((Number)r.get("id")).intValue()==space).findFirst().orElseThrow().get("spaces_flag")).intValue());
    }
    @Test void concurrentMembershipRenewalsAccumulateInsteadOfOverwriting() throws Exception {
        membership.change(user,30,false);LocalDateTime before=db.queryForObject("select vip_end_time from user where id=?",LocalDateTime.class,user);
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->membership.change(user,10,true));var b=pool.submit(()->membership.change(user,10,true));a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(before.plusDays(20),db.queryForObject("select vip_end_time from user where id=?",LocalDateTime.class,user));
    }
    @Test void cacheInvalidationHappensAfterCommitAndNotAfterRollback() {
        String resource="it:"+prefix;var count=new java.util.concurrent.atomic.AtomicInteger();var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Integer>>(){};
        java.util.function.Supplier<Map<String,Integer>> loader=()->Map.of("n",count.incrementAndGet());
        assertEquals(1,cache.get(resource,"key",java.time.Duration.ofMinutes(1),type,loader).get("n"));
        assertEquals(1,cache.get(resource,"key",java.time.Duration.ofMinutes(1),type,loader).get("n"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{cache.invalidateAfterCommit(resource);status.setRollbackOnly();});
        assertEquals(1,cache.get(resource,"key",java.time.Duration.ofMinutes(1),type,loader).get("n"));
        tx.executeWithoutResult(status->cache.invalidateAfterCommit(resource));
        assertEquals(2,cache.get(resource,"key",java.time.Duration.ofMinutes(1),type,loader).get("n"));
    }
    @Test void cacheFailureFallsBackAndCircuitPreventsRepeatedTimeouts() {
        var isolated=new ReadCache(new com.fasterxml.jackson.databind.ObjectMapper(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),true,"127.0.0.1",1,"");
        try {var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Integer>>(){};assertEquals(7,isolated.get("failure","key",java.time.Duration.ofSeconds(1),type,()->Map.of("n",7)).get("n"));long start=System.nanoTime();assertEquals(8,isolated.get("failure","key",java.time.Duration.ofSeconds(1),type,()->Map.of("n",8)).get("n"));assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(200));}finally{isolated.close();}
    }
    @Test void addingAUserPreservesSubmittedProfileAndDoesNotAcceptPrivilegedFields() {
        int admin=db.queryForObject("select min(id) from admin where permissions=1",Integer.class);String auth=token(admin,"integration-super",1);
        var h=new HttpHeaders();h.set("Authorization",auth);h.setContentType(MediaType.APPLICATION_JSON);
        String name=prefix+"new";
        var result=http.postForEntity("/user/addUser",new HttpEntity<>(Map.of("user_name",name,"name","测试","plate_num","沪A-12345","car_color","白色","tel_num","13912345678","balance",99999),h),Map.class);
        try {assertEquals(0,result.getBody().get("code"));assertEquals("测试",db.queryForObject("select name from user where user_name=?",String.class,name));assertEquals(0,db.queryForObject("select balance from user where user_name=?",BigDecimal.class,name).signum());} finally{db.update("delete from user where user_name=?",name);}
    }

    @Test void administratorPasswordChangeChecksOldPasswordAndRevokesToken() {
        String name=prefix+"pw";
        int id=insert("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",name,Md5Util.encode("test12345"));
        String auth=token(id,name,0);var h=new HttpHeaders();h.set("Authorization",auth);h.setContentType(MediaType.APPLICATION_JSON);
        try {
            var invalid=http.exchange("/admin/updatePassword",HttpMethod.PATCH,new HttpEntity<>(Map.of("old_pwd","incorrect","new_pwd","test54321","re_pwd","test54321"),h),Map.class);
            assertEquals(1,invalid.getBody().get("code"));
            var valid=http.exchange("/admin/updatePassword",HttpMethod.PATCH,new HttpEntity<>(Map.of("old_pwd","test12345","new_pwd","test54321","re_pwd","test54321"),h),Map.class);
            assertEquals(0,valid.getBody().get("code"));assertEquals(401,get("/admin/adminInfo",auth).getStatusCode().value());
        } finally {db.update("delete from admin where id=?",id);}
    }

    @Test void cancellationAndExpiryCompeteWithoutDoubleRelease() throws Exception {
        int id=flow.reserve(user,space).getId();db.update("update enter set expires_at=? where id=?",LocalDateTime.now(clock).minusSeconds(1),id);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->{start.await();flow.cancel(user,id);return true;});var b=pool.submit(()->{start.await();flow.expire(id);return true;});start.countDown();assertTrue(a.get(5,TimeUnit.SECONDS));assertTrue(b.get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertEquals(2,available());assertNull(db.queryForObject("select active_enter_id from spaces where id=?",Integer.class,space));
    }
    @Test void arrivingAtAnExpiredReservationCannotBeReleasedByTheOldMessage() throws Exception {
        int old=flow.reserve(user,space).getId();db.update("update enter set expires_at=? where id=?",LocalDateTime.now(clock).minusSeconds(1),old);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->{start.await();flow.enter(plate,park);return true;});var b=pool.submit(()->{start.await();flow.expire(old);return true;});start.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        flow.expire(old);assertEquals(1,available());assertEquals(1,db.queryForObject("select count(*) from enter where active_user_id=? and flag=1",Integer.class,user));
        assertNotEquals(old,db.queryForObject("select active_enter_id from spaces where spaces_flag=1 and park_id=?",Integer.class,park));
    }
    @Test void cancellationRacingArrivalLeavesExactlyOneInLotRecord() throws Exception {
        int id=flow.reserve(user,space).getId();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->{start.await();try{flow.cancel(user,id);}catch(IllegalStateException expected){}return true;});var b=pool.submit(()->{start.await();flow.enter(plate,park);return true;});start.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(1,available());assertEquals(1,db.queryForObject("select count(*) from enter where active_user_id=? and flag=1",Integer.class,user));
    }
    @Test void distinctSpaceReservationsDoNotLoseCapacityUpdates() throws Exception {
        var pool=Executors.newFixedThreadPool(2);try {var a=pool.submit(()->flow.reserve(user,space));var b=pool.submit(()->flow.reserve(user2,space2));int first=a.get(5,TimeUnit.SECONDS).getId(),second=b.get(5,TimeUnit.SECONDS).getId();assertEquals(0,available());flow.cancel(user,first);flow.cancel(user2,second);assertEquals(2,available());}finally{pool.shutdownNow();}
    }
    @Test void concurrentPaymentCallbacksCreditOnce() throws Exception {
        int id=insert("insert into recharges(user_id,amounts,flag,pay_method,create_time) values(?,12.34,0,0,now())",user);
        var params=Map.of("app_id","integration-app","trade_status","TRADE_SUCCESS","out_trade_no",Integer.toString(id),"total_amount","12.34","trade_no",prefix,"gmt_payment","2026-10-17 12:00:00");
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->payments.confirm(params));var b=pool.submit(()->payments.confirm(params));a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(0,new BigDecimal("32.34").compareTo(db.queryForObject("select balance from user where id=?",BigDecimal.class,user)));
    }
    @Test void profileUpdatesUseTheAuthenticatedUserAndIgnoreBalance() {
        String auth=token(user,prefix,-1);var h=new HttpHeaders();h.set("Authorization",auth);h.setContentType(MediaType.APPLICATION_JSON);
        var payload=Map.of("id",user2,"name","本人","plate_num",plate,"car_color","白色","tel_num","13912345678","balance",99999,"status",1);
        assertEquals(0,http.exchange("/user/update",HttpMethod.PUT,new HttpEntity<>(payload,h),Map.class).getBody().get("code"));
        assertEquals("本人",db.queryForObject("select name from user where id=?",String.class,user));assertEquals("",db.queryForObject("select name from user where id=?",String.class,user2));assertEquals(0,new BigDecimal("20").compareTo(db.queryForObject("select balance from user where id=?",BigDecimal.class,user)));assertEquals(0,db.queryForObject("select status from user where id=?",Integer.class,user));
    }
    @Test void dateFilteringIncludesStartAndExcludesNextMidnight() {
        var date=java.time.LocalDate.now(clock).minusDays(1);
        for(var time:List.of(date.atStartOfDay(),date.plusDays(1).atStartOfDay())) db.update("insert into orders(plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time) values(?,?,?,?,?,0,0,?)",plate,user,park,time,time,time);
        assertEquals(1,orders.rootGetPageList(1,20,prefix,null,date,null).getTotal());
    }
    @Test void aLateCacheFillCannotOverwriteANewVersion() throws Exception {
        String resource="race:"+prefix;var loaded=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Integer>>(){};
        try {var old=pool.submit(()->cache.get(resource,"key",java.time.Duration.ofMinutes(1),type,()->{loaded.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return Map.of("n",1);}));assertTrue(loaded.await(5,TimeUnit.SECONDS));cache.invalidateAfterCommit(resource);release.countDown();assertEquals(1,old.get(5,TimeUnit.SECONDS).get("n"));assertEquals(2,cache.get(resource,"key",java.time.Duration.ofMinutes(1),type,()->Map.of("n",2)).get("n"));}finally{release.countDown();pool.shutdownNow();}
    }

    @Autowired AccessGuard guard;
    @Test void ownershipIsRecheckedAfterOcrBeforeTheVehicleTransaction() {
        int old=insert("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",prefix+"owner",Md5Util.encode("test12345"));
        int owner=db.queryForObject("select admin_id from parking where id=?",Integer.class,park);
        try {
            db.update("update parking set admin_id=? where id=?",old,park);
            org.zjmproject.utils.ThreadLocalUtil.set(Map.of("id",old,"permissions",0));guard.parking(park);
            db.update("update parking set admin_id=? where id=?",owner,park);
            assertThrows(IllegalArgumentException.class,()->flow.enter(plate,park));assertEquals(2,available());
        }finally{org.zjmproject.utils.ThreadLocalUtil.remove();db.update("update parking set admin_id=? where id=?",owner,park);db.update("delete from admin where id=?",old);}
    }
    @Test void authorizationUsesCurrentOwnerInsteadOfAnOldTransactionSnapshot() throws Exception {
        int old=insert("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",prefix+"snapshot",Md5Util.encode("test12345"));
        int owner=db.queryForObject("select admin_id from parking where id=?",Integer.class,park);
        var snapshot=new CountDownLatch(1);var changed=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            db.update("update parking set admin_id=? where id=?",old,park);
            var attempt=pool.submit(()->{org.zjmproject.utils.ThreadLocalUtil.set(Map.of("id",old,"permissions",0));try{return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{db.queryForObject("select admin_id from parking where id=?",Integer.class,park);snapshot.countDown();try{changed.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}try{guard.parking(park);return false;}catch(IllegalArgumentException expected){return true;}});}finally{org.zjmproject.utils.ThreadLocalUtil.remove();}});
            assertTrue(snapshot.await(5,TimeUnit.SECONDS));db.update("update parking set admin_id=? where id=?",owner,park);changed.countDown();assertTrue(attempt.get(5,TimeUnit.SECONDS));
        }finally{changed.countDown();pool.shutdownNow();db.update("update parking set admin_id=? where id=?",owner,park);db.update("delete from admin where id=?",old);}
    }

    @Test void aMissingParkingCacheIsInvalidatedWhenTheParkingIsCreated() {
        int owner=db.queryForObject("select admin_id from parking where id=?",Integer.class,park2);
        db.update("delete from parking where id=?",park2);String auth=token(user,prefix,-1);
        assertEquals(1,get("/spaces/layout?park_id="+park2+"&spaces_storey=1",auth).getBody().get("code"));
        db.update("insert into parking(id,admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) values(?,?,?,'test',0,0,1,?,now(),now())",park2,owner,prefix+"b",rule);
        cache.invalidateAfterCommit("catalog");
        assertEquals(0,get("/spaces/layout?park_id="+park2+"&spaces_storey=1",auth).getBody().get("code"));
    }
    @Test void parkingPagesSupportEmptyAndCombinedFiltersAndOwnerScope() {
        int root=db.queryForObject("select min(id) from admin where permissions=1",Integer.class);
        String superToken=token(root,prefix+"root",1);
        for(String query:List.of("", "&park_name=&park_address=&admin_name=&rule_name=", "&park_name="+prefix+"&park_address=test&rule_name="+prefix)) {
            var result=get("/parking/selectAllParking?pageNum=1&pageSize=20"+query,superToken);
            assertEquals(200,result.getStatusCode().value());assertEquals(0,result.getBody().get("code"));
        }
        int owner=insert("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",prefix+"park",Md5Util.encode("test12345"));
        try {
            db.update("update parking set admin_id=? where id=?",owner,park2);
            String auth=token(owner,prefix+"park",0);
            for(String filters:List.of("", "&park_name=&park_address=&rule_name=", "&park_name="+prefix+"&park_address=test&rule_name="+prefix)) {
                var body=get("/parking/selectParking?pageNum=1&pageSize=1"+filters,auth).getBody();assertEquals(0,body.get("code"));
                var page=(Map)body.get("data");assertEquals(1,((Number)page.get("total")).intValue());assertEquals(park2,((Number)((Map)((List)page.get("items")).get(0)).get("id")).intValue());
            }
        }finally{db.update("update parking set admin_id=? where id=?",root,park2);db.update("delete from admin where id=?",owner);}
    }
    @Test void parkingCrudValidatesReferencesAndPreservesLinkedRecords() {
        int root=db.queryForObject("select min(id) from admin where permissions=1",Integer.class);String auth=token(root,prefix+"crud",1);
        var headers=new HttpHeaders();headers.set("Authorization",auth);headers.setContentType(MediaType.APPLICATION_JSON);
        var values=new HashMap<String,Object>(Map.of("park_name",prefix+"new","park_address","test","park_storey",1,"admin_id",root,"rule_id",rule));
        try {
            values.put("rule_id",2147483647);var invalid=http.postForEntity("/parking/rootAddParking",new HttpEntity<>(values,headers),Map.class);assertEquals(1,invalid.getBody().get("code"));assertTrue(invalid.getBody().get("message").toString().contains("计费规则不存在"));
            values.put("rule_id",rule);values.put("admin_id",2147483647);assertEquals(1,http.postForEntity("/parking/rootAddParking",new HttpEntity<>(values,headers),Map.class).getBody().get("code"));
            values.put("admin_id",root);assertEquals(0,http.postForEntity("/parking/rootAddParking",new HttpEntity<>(values,headers),Map.class).getBody().get("code"));
            int id=db.queryForObject("select id from parking where park_name=?",Integer.class,prefix+"new");values.put("id",id);values.put("park_address","updated");
            assertEquals(0,http.exchange("/parking/rootUpdateParking",HttpMethod.PUT,new HttpEntity<>(values,headers),Map.class).getBody().get("code"));
            assertEquals("updated",db.queryForObject("select park_address from parking where id=?",String.class,id));
            assertEquals(1,http.exchange("/parking/delete?park_name="+prefix,HttpMethod.DELETE,new HttpEntity<>(headers),Map.class).getBody().get("code"));
            assertEquals(2,db.queryForObject("select count(*) from spaces where park_id=?",Integer.class,park));
            assertEquals(0,http.exchange("/parking/delete?park_name="+prefix+"new",HttpMethod.DELETE,new HttpEntity<>(headers),Map.class).getBody().get("code"));
        }finally{db.update("delete from parking where park_name=?",prefix+"new");}
    }
    @Test void localMetadataCacheIsBoundedShortLivedAndDoesNotCacheTransactions() throws Exception {
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var localCache=new ReadCache(new com.fasterxml.jackson.databind.ObjectMapper(),registry,true,"127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("TEST_CACHE_PORT","26381")),System.getenv("CACHE_REDIS_PASSWORD"),true,java.time.Duration.ofMillis(100),16777216);
        var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Integer>>(){};var loads=new java.util.concurrent.atomic.AtomicInteger();
        try {
            String key=prefix+"local";java.util.function.Supplier<Map<String,Integer>> loader=()->Map.of("n",loads.incrementAndGet());
            assertEquals(1,localCache.get("catalog",key,java.time.Duration.ofMinutes(1),type,loader).get("n"));
            var copy=localCache.get("catalog",key,java.time.Duration.ofMinutes(1),type,loader);copy.put("n",999);
            assertEquals(1,localCache.get("catalog",key,java.time.Duration.ofMinutes(1),type,loader).get("n"));
            assertTrue(registry.get("parking.cache.local.hit").counter().count()>=2);
            Thread.sleep(150);localCache.get("catalog",key,java.time.Duration.ofMinutes(1),type,loader);
            assertEquals(1,loads.get());assertTrue(registry.get("parking.cache.redis.hit").counter().count()>0);
            localCache.invalidateAfterCommit("catalog");assertEquals(2,localCache.get("catalog",key,java.time.Duration.ofMinutes(1),type,loader).get("n"));
            double before=registry.get("parking.cache.local.hit").counter().count();
            localCache.get("dashboard",key,java.time.Duration.ofSeconds(30),type,loader);localCache.get("dashboard",key,java.time.Duration.ofSeconds(30),type,loader);
            assertEquals(before,registry.get("parking.cache.local.hit").counter().count());
        }finally{localCache.close();}
    }
    @Test void databaseLoaderFailureIsNotRetriedAsACacheFailure() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Integer>>(){};
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->cache.get("it:"+prefix,"failure",java.time.Duration.ofSeconds(30),type,()->{calls.incrementAndGet();throw new org.springframework.dao.DataIntegrityViolationException("test-only");}));
        assertEquals(1,calls.get());
    }

    @Test void failedInvalidationDisablesOldLocalAndRedisEntriesUntilNamespaceRecovery() {
        var localCache=new ReadCache(new com.fasterxml.jackson.databind.ObjectMapper(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),true,"127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("TEST_CACHE_PORT","26381")),System.getenv("CACHE_REDIS_PASSWORD"),true,java.time.Duration.ofSeconds(1),16777216);
        var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Integer>>(){};
        try {
            assertEquals(1,localCache.get("catalog",prefix,java.time.Duration.ofMinutes(1),type,()->Map.of("n",1)).get("n"));
            var factory=(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory)org.springframework.test.util.ReflectionTestUtils.getField(localCache,"factory");
            factory.stop();localCache.invalidateAfterCommit("catalog");
            assertEquals(2,localCache.get("catalog",prefix,java.time.Duration.ofMinutes(1),type,()->Map.of("n",2)).get("n"));
            factory.start();org.springframework.test.util.ReflectionTestUtils.setField(localCache,"unavailableUntil",0L);
            assertEquals(2,localCache.get("catalog",prefix,java.time.Duration.ofMinutes(1),type,()->Map.of("n",2)).get("n"));
            assertEquals(2,localCache.get("catalog",prefix,java.time.Duration.ofMinutes(1),type,()->Map.of("n",3)).get("n"));
        }finally{localCache.close();}
    }
    @Autowired ParkingAdmission admission;
    @Autowired javax.sql.DataSource dataSource;
    @Test void queuedParkingWriterWaitsBeforeAcquiringAJdbcConnection() throws Exception {
        int permits=(Integer)org.springframework.test.util.ReflectionTestUtils.getField(admission,"permits");
        var entered=new CountDownLatch(permits);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(permits+1);
        var leaders=new java.util.ArrayList<Future<Boolean>>();
        try {
            for(int i=0;i<permits;i++)leaders.add(pool.submit(()->admission.execute(park,()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}return true;})));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second=pool.submit(()->{secondStarted.countDown();return admission.execute(park,()->true);});assertTrue(secondStarted.await(5,TimeUnit.SECONDS));
            Thread.sleep(100);assertFalse(second.isDone());
            assertTrue(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()<=permits+1,"Queued requests must not fill the JDBC pool");
            release.countDown();for(var leader:leaders)assertTrue(leader.get(5,TimeUnit.SECONDS));assertTrue(second.get(5,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();}
    }

}
