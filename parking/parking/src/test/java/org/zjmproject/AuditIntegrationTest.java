package org.zjmproject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.zjmproject.observability.RuntimeEvents;
import org.zjmproject.service.AuditService;
import org.zjmproject.utils.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PARKING_INTEGRATION_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
    "spring.data.redis.port=${TEST_REDIS_PORT}","parking.background.enabled=false"})
class AuditIntegrationTest {
    @Autowired AuditService audit;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired TestRestTemplate http;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    int actor;String correlation;
    @BeforeEach void prepare() {
        actor=db.queryForObject("select min(id) from admin where permissions=1",Integer.class);
        ThreadLocalUtil.set(Map.of("id",actor,"permissions",1));correlation=UUID.randomUUID().toString();org.slf4j.MDC.put("requestId",correlation);
    }
    @AfterEach void clear() {db.update("delete from admin_audit where request_id=?",correlation);org.slf4j.MDC.clear();ThreadLocalUtil.remove();}
    @Test void disabledMqStillRecordsAndRollbackNeverClaimsSuccess() {
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(t->{audit.success("parking.update",List.of(1));t.setRollbackOnly();});
        assertEquals(0,count());
        tx.executeWithoutResult(t->audit.success("parking.update",List.of(1)));
        assertEquals(1,count());assertEquals(0,db.queryForObject("select count(*) from background_event where request_id=?",Integer.class,correlation));
        var page=audit.page(1,20,null,null,actor,"parking.update","SUCCESS",correlation);
        assertEquals(1L,page.get("total"));
    }
    int count(){return db.queryForObject("select count(*) from admin_audit where request_id=?",Integer.class,correlation);}
    @Test void independentFailuresSurviveRollbackAndUnknownActorsAreAllowed() {
        new TransactionTemplate(manager).executeWithoutResult(t->{audit.bestEffort("admin.login",List.of(),null,null,"FAILURE","REQUEST_REJECTED");t.setRollbackOnly();});
        assertEquals(1,count());
        assertNull(db.queryForMap("select actor_id from admin_audit where request_id=?",correlation).get("actor_id"));
    }
    @Test void oldPayloadsRemainReadableAndRepeatedDeliveryIsIdempotent() {
        String id=UUID.randomUUID().toString();
        try {
            Map<String,Object> old=Map.of("action","parking.update","ids",List.of(1));
            audit.persist(id,actor,java.time.LocalDateTime.now(),old);audit.persist(id,actor,java.time.LocalDateTime.now(),old);
            assertEquals(1,db.queryForObject("select count(*) from admin_audit where event_id=?",Integer.class,id));
            assertNull(audit.detail(id).get("requestId"));assertNull(audit.detail(id).get("result"));
        } finally {db.update("delete from admin_audit where event_id=?",id);}
    }
    @Test void malformedAuditIntentIsNotSilentlyAccepted() {
        String id=UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,()->audit.persist(id,actor,java.time.LocalDateTime.now(),Map.of("ids",List.of(1))));
        assertEquals(0,db.queryForObject("select count(*) from admin_audit where event_id=?",Integer.class,id));
    }
    @Test void scopeAndQueryBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,()->audit.page(1,101,null,null,null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->audit.page(1,20,java.time.LocalDateTime.now().minusDays(32),null,null,null,null,null));
        ThreadLocalUtil.set(Map.of("id",actor,"permissions",0));
        assertThrows(IllegalArgumentException.class,()->audit.page(1,20,null,null,null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->audit.detail(UUID.randomUUID().toString()));
        assertEquals(HttpStatus.UNAUTHORIZED,http.getForEntity("/admin/audit/page",String.class).getStatusCode());
        String identity="audit-test-role";String token=JwtUtil.genToken(Map.of("id",actor,"permissions",0,"adminname",identity));
        redis.opsForValue().set("admin="+identity,token);
        try {
            var headers=new HttpHeaders();headers.set("Authorization",token);
            assertEquals(HttpStatus.FORBIDDEN,http.exchange("/admin/audit/page",HttpMethod.GET,new HttpEntity<>(headers),String.class).getStatusCode());
        } finally {redis.delete("admin="+identity);}
    }
    @Test void loginFailureRecordsNoSubmittedCredentials() {
        var form=new LinkedMultiValueMap<String,String>();form.add("username","unknownAudit");form.add("password","secret12345");
        var headers=new HttpHeaders();headers.set("X-Request-ID",correlation);headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var response=http.postForEntity("/admin/login",new HttpEntity<>(form,headers),String.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());assertEquals(correlation,response.getHeaders().getFirst("X-Request-ID"));
        assertEquals(1,count());var row=db.queryForMap("select * from admin_audit where request_id=?",correlation);
        assertEquals("FAILURE",row.get("result"));assertFalse(row.toString().contains("secret12345"));assertFalse(row.toString().contains("unknownAudit"));
    }
}
