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
    "parking.integrations.alipay=true","alipay.app-id=integration-app"
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
}
