package org.zjmproject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.zjmproject.service.*;
import org.zjmproject.pojo.FormContent;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Run CREATE and VERIFY in separate JVMs using the same disposable SQL/Redis volumes. */
@EnabledIfEnvironmentVariable(named="PARKING_PAYMENT_RESTART_PHASE",matches="CREATE|VERIFY")
@SpringBootTest(properties={"spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
    "spring.data.redis.port=${TEST_REDIS_PORT}","parking.integrations.alipay=true","alipay.app-id=integration-app"})
class PaymentRestartIntegrationTest {
    @TestConfiguration static class Provider {
        @Bean @Primary AliPayService restartProvider(Clock clock) {return new AliPayService() {
            public FormContent<String> pay(Integer id){throw new UnsupportedOperationException();}
            public Map<String,String> rechargePayNotify(jakarta.servlet.http.HttpServletRequest r){return null;}
            public boolean close(String no){return false;}
            public PaymentTrade query(String no){return no.startsWith("RST")?new PaymentTrade(no,"trade-"+no,"TRADE_SUCCESS",BigDecimal.TEN,LocalDateTime.now(clock)):new PaymentTrade(no,null,"NOT_FOUND",null,null);}
        };}
    }
    @Autowired JdbcTemplate db;
    @Autowired org.redisson.api.RedissonClient redis;
    @Autowired Clock clock;
    @Test @EnabledIfEnvironmentVariable(named="PARKING_PAYMENT_RESTART_PHASE",matches="CREATE")
    void createInterruptedDurableWork() {
        assertEquals("parking-payment-verification",System.getenv("PARKING_COMPOSE_PROJECT"));
        String name="pay-restart-fixture";assertEquals(0,db.queryForObject("select count(*) from user where user_name=?",Integer.class,name));
        db.update("insert into user(user_name,password,balance,create_time,update_time) values(?,'test-only',20,now(),now())",name);
        int user=db.queryForObject("select id from user where user_name=?",Integer.class,name);
        db.update("insert into recharges(user_id,amounts,pay_method,create_time,merchant_order_no,payment_state) values(?,10,0,?,?,'VERIFYING')",user,LocalDateTime.now(clock),"RST"+UUID.randomUUID().toString().replace("-",""));
        int id=db.queryForObject("select id from recharges where user_id=?",Integer.class,user);
        db.update("insert into payment_event(recharge_id,next_run_at,lease_token,lease_until) values(?,?,'interrupted',?)",id,LocalDateTime.now(clock).plusSeconds(5),LocalDateTime.now(clock).plusSeconds(5));
        redis.<String>getDeque(PaymentQueue.PROCESSING).add(id+":1");
        assertEquals("VERIFYING",db.queryForObject("select payment_state from recharges where id=?",String.class,id));
    }
    @Test @EnabledIfEnvironmentVariable(named="PARKING_PAYMENT_RESTART_PHASE",matches="VERIFY")
    void aNewApplicationProcessRestoresAndCreditsExactlyOnce() throws Exception {
        int user=db.queryForObject("select id from user where user_name='pay-restart-fixture'",Integer.class);
        int id=db.queryForObject("select id from recharges where user_id=?",Integer.class,user);
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(System.nanoTime()<until && db.queryForObject("select flag from recharges where id=?",Integer.class,id)!=1) Thread.sleep(100);
            assertEquals(1,db.queryForObject("select flag from recharges where id=?",Integer.class,id));
            assertEquals(0,new BigDecimal("30").compareTo(db.queryForObject("select balance from user where id=?",BigDecimal.class,user)));
            assertNotNull(db.queryForObject("select completed_at from payment_event where recharge_id=?",Object.class,id));
        } finally {db.update("delete from recharges where user_id=?",user);db.update("delete from user where id=?",user);}
    }
}
