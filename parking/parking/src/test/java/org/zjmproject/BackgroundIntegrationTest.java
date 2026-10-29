package org.zjmproject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.zjmproject.service.BackgroundTasks;
import org.zjmproject.config.BackgroundConfig;
import org.zjmproject.utils.ThreadLocalUtil;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PARKING_BACKGROUND_TESTS",matches="true")
@SpringBootTest(properties={"spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
        "spring.data.redis.port=${TEST_REDIS_PORT:26380}","parking.background.enabled=true",
        "parking.background.export-dir=target/test-exports","spring.rabbitmq.port=${TEST_RABBIT_PORT:25672}",
        "spring.rabbitmq.password=${RABBITMQ_PASSWORD}","spring.rabbitmq.listener.simple.auto-startup=true"})
class BackgroundIntegrationTest {
    @Autowired BackgroundTasks tasks;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired org.zjmproject.service.ParkingFlowService flow;
    int actor;String id;
    @BeforeEach void actor(){actor=db.queryForObject("select min(id) from admin where permissions=1",Integer.class);ThreadLocalUtil.set(Map.of("id",actor,"permissions",1));}
    @AfterEach void cleanup() throws Exception {
        ThreadLocalUtil.remove();
        if(id!=null){for(var name:db.queryForList("select result_file from background_event where id=? and result_file is not null",String.class,id))Files.deleteIfExists(Path.of("target/test-exports",name));db.update("delete from admin_audit where event_id=?",id);db.update("delete from background_event where id=?",id);}
    }
    void await(String desired) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){if(desired.equals(db.queryForObject("select status from background_event where id=?",String.class,id)))return;Thread.sleep(100);}
        assertEquals(desired,db.queryForObject("select status from background_event where id=?",String.class,id));
    }
    @Test void confirmedBrokerDeliveryExportsAndDuplicateMessagesDoNotRepeatTheEffect() throws Exception {
        String key=UUID.randomUUID().toString();id=tasks.createExport(key,Map.of("user_name","nonexistent-background-test"));
        assertEquals(id,tasks.createExport(key,Map.of()));await("DONE");Path file=tasks.download(id);
        assertTrue(Files.readString(file).contains("用户名"));
        tasks.consume(id);assertEquals(file,tasks.download(id));
        db.update("update background_event set expires_at=date_sub(now(3),interval 1 second) where id=?",id);
        var originalZone=TimeZone.getDefault();
        try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertThrows(IllegalStateException.class,()->tasks.download(id));assertEquals("EXPIRED",tasks.status(id).get("status"));}
        finally{TimeZone.setDefault(originalZone);}
        tasks.cleanup();assertFalse(Files.exists(file));
    }
    @Test void ownershipAndRootRoleAreBothRequired() {
        id=tasks.createExport(UUID.randomUUID().toString(),Map.of("user_name","absent"));
        ThreadLocalUtil.set(Map.of("id",actor,"permissions",0));assertThrows(IllegalArgumentException.class,()->tasks.status(id));
        ThreadLocalUtil.set(Map.of("id",actor+999999,"permissions",1));assertThrows(IllegalArgumentException.class,()->tasks.status(id));
    }
    @Test void exportCrossesTheFiveHundredRowCursorBoundaryAndEscapesSpreadsheetFormulas() throws Exception {
        String prefix="bx"+UUID.randomUUID().toString().replace("-","").substring(0,8);
        try {
            db.batchUpdate("insert into user(user_name,password,name,create_time,update_time) values(?,'test-only',?,now(),now())",
                java.util.stream.IntStream.range(0,501).mapToObj(n->new Object[]{prefix+n,n==500?"=SUM(1,2)":"test"}).toList());
            id=tasks.createExport(UUID.randomUUID().toString(),Map.of("user_name",prefix));await("DONE");
            String contents=Files.readString(tasks.download(id));assertEquals(502,contents.lines().count());assertTrue(contents.contains("'\u003dSUM(1,2)"));
        } finally {db.update("delete from user where user_name like ?",prefix+"%");}
    }
    @Test void rollbackDoesNotLeakAnAuditEvent() {
        String action="rollback."+UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(t->{tasks.audit(action,List.of(1));t.setRollbackOnly();});
        assertEquals(0,db.queryForObject("select count(*) from background_event where json_unquote(json_extract(payload,'$.action'))=?",Integer.class,action));
    }
    @Test void expiredWorkerLeaseIsRecoveredAndAuditIsIdempotent() throws Exception {
        id=UUID.randomUUID().toString();
        db.update("insert into background_event(id,actor_id,kind,request_key,payload,status,available_at) values(?,?,'AUDIT',?,'{\"action\":\"test.recovery\",\"ids\":[1]}','RUNNING',date_sub(now(3),interval 1 second))",id,actor,id);
        await("DONE");tasks.consume(id);assertEquals(1,db.queryForObject("select count(*) from admin_audit where event_id=?",Integer.class,id));
    }
    @Test void poisonEventHasBoundedRetriesAndCanBeReplayed() throws Exception {
        id=UUID.randomUUID().toString();
        db.update("insert into background_event(id,actor_id,kind,request_key,payload,status,attempts,available_at) values(?,?,'INVALID',?,'{}','SENT',4,date_add(now(3),interval 5 minute))",id,actor,id);
        tasks.consume(id);assertEquals("DEAD_PENDING",db.queryForObject("select status from background_event where id=?",String.class,id));
        db.update("update background_event set available_at=now(3) where id=?",id);await("DEAD");
        db.update("update background_event set kind='AUDIT',payload='{\"action\":\"test.replay\",\"ids\":[1]}' where id=?",id);
        tasks.replay(id);await("DONE");assertEquals(1,db.queryForObject("select count(*) from admin_audit where event_id=?",Integer.class,id));
    }
    @Test void unroutablePublicationIsReturnedEvenIfPublisherConfirmAcknowledgesIt() throws Exception {
        var data=new CorrelationData(UUID.randomUUID().toString());
        rabbit.convertAndSend(BackgroundConfig.EXCHANGE,"unbound-test-routing-key",UUID.randomUUID().toString(),data);
        assertTrue(data.getFuture().get(5,TimeUnit.SECONDS).isAck());assertNotNull(data.getReturned());
    }
    @Test @EnabledIfEnvironmentVariable(named="PARKING_DOCKER_RECOVERY_TESTS",matches="true")
    void durableQueueSurvivesBrokerRestartAndCoreWritesContinueDuringTheOutage() throws Exception {
        assertTrue(Set.of("parking-verification","parking-logging-verification").contains(System.getenv("PARKING_COMPOSE_PROJECT")));
        String suffix=UUID.randomUUID().toString().substring(0,8);int uid=0,park=0,rule=0;
        String correlation="restart-"+UUID.randomUUID();
        listeners.stop();
        try {
            id=tasks.createExport(UUID.randomUUID().toString(),Map.of("user_name","absent-restart-"+suffix));await("SENT");
            docker("stop");
            try(var scope=org.zjmproject.observability.RuntimeEvents.scope(correlation)) {
                new TransactionTemplate(manager).executeWithoutResult(t->tasks.audit("test.broker_outage",List.of(1)));
            }
            assertEquals(1,db.queryForObject("select count(*) from background_event where request_id=?",Integer.class,correlation));
            db.update("insert into user(user_name,password,plate_num,create_time,update_time) values(?,'test','京A-12345',now(),now())","br"+suffix);
            uid=db.queryForObject("select id from user where user_name=?",Integer.class,"br"+suffix);
            db.update("insert into rule(rule_name,free_time,charging_unit,unit_cost,create_time,update_time) values(?,0,30,1,now(),now())","br"+suffix);
            rule=db.queryForObject("select id from rule where rule_name=?",Integer.class,"br"+suffix);
            db.update("insert into parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) values(?,?,'isolated',0,0,1,?,now(),now())",actor,"br"+suffix,rule);
            park=db.queryForObject("select id from parking where park_name=?",Integer.class,"br"+suffix);
            db.update("insert into spaces(park_id,spaces_name,spaces_col,spaces_row,spaces_storey,spaces_flag,create_time,update_time) values(?,'A1',1,1,1,0,now(),now())",park);
            flow.refreshCapacity(park);
            int space=db.queryForObject("select id from spaces where park_id=?",Integer.class,park);
            int reservation=flow.reserve(uid,space).getId();flow.cancel(uid,reservation);
            assertEquals(1,db.queryForObject("select park_spare from parking_capacity_total where park_id=?",Integer.class,park));
            assertEquals("SENT",db.queryForObject("select status from background_event where id=?",String.class,id));
        } finally {
            docker("start");listeners.start();
            if(uid!=0){db.update("delete from reservation_event where reservation_id in(select id from enter where user_id=?)",uid);db.update("delete from enter where user_id=?",uid);}
            if(park!=0){db.update("delete from spaces where park_id=?",park);db.update("delete from parking where id=?",park);}
            if(rule!=0)db.update("delete from rule where id=?",rule);
            if(uid!=0)db.update("delete from user where id=?",uid);
        }
        await("DONE");assertTrue(Files.isRegularFile(tasks.download(id)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline && db.queryForObject("select count(*) from admin_audit where request_id=?",Integer.class,correlation)==0)Thread.sleep(100);
        assertEquals(1,db.queryForObject("select count(*) from admin_audit where request_id=?",Integer.class,correlation));
        String auditId=db.queryForObject("select event_id from admin_audit where request_id=?",String.class,correlation);
        tasks.consume(auditId);
        assertEquals(1,db.queryForObject("select count(*) from admin_audit where request_id=?",Integer.class,correlation));
        db.update("delete from admin_audit where request_id=?",correlation);
        db.update("delete from background_event where request_id=?",correlation);
    }
    private void docker(String action) throws Exception {
        String project=System.getenv("PARKING_COMPOSE_PROJECT");
        assertTrue(Set.of("parking-verification","parking-logging-verification").contains(project));
        var p=new ProcessBuilder("docker",action,project+"-rabbitmq-1").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(30,TimeUnit.SECONDS));assertEquals(0,p.exitValue());
    }
}
