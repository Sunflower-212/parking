package org.zjmproject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.zjmproject.service.*;
import org.zjmproject.service.LayoutEditorService.*;
import org.zjmproject.utils.ThreadLocalUtil;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PARKING_INTEGRATION_TESTS",matches="true")
@SpringBootTest(properties={"spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}","spring.data.redis.port=${TEST_REDIS_PORT}","parking.background.enabled=false"})
class LayoutEditorIntegrationTest {
    @Autowired LayoutEditorService editor; @Autowired JdbcTemplate db; @Autowired ParkingFlowService flow; @Autowired FloorService floors;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    int actor,park,rule,user;String correlation;
    void actor(){ThreadLocalUtil.set(Map.of("id",actor,"permissions",1));}
    @BeforeEach void setup(){
        actor=db.queryForObject("select min(id) from admin where permissions=1",Integer.class);actor();
        String name="map"+UUID.randomUUID().toString().substring(0,8);correlation=UUID.randomUUID().toString();org.slf4j.MDC.put("requestId",correlation);
        db.update("insert into rule(rule_name,free_time,charging_unit,unit_cost,create_time,update_time) values(?,0,30,1,now(),now())",name);
        rule=db.queryForObject("select id from rule where rule_name=?",Integer.class,name);
        db.update("insert into parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) values(?,?,'isolated',0,0,2,?,now(),now())",actor,name,rule);
        park=db.queryForObject("select id from parking where park_name=?",Integer.class,name);
        db.update("insert into user(user_name,password,plate_num,create_time,update_time) values(?,'test','京A-12345',now(),now())",name);
        user=db.queryForObject("select id from user where user_name=?",Integer.class,name);
    }
    @AfterEach void cleanup(){
        db.update("delete from reservation_event where reservation_id in(select id from enter where user_id=?)",user);db.update("delete from enter where user_id=?",user);
        db.update("delete from spaces where park_id=?",park);db.update("delete from parking where id=?",park);db.update("delete from rule where id=?",rule);db.update("delete from user where id=?",user);
        db.update("delete from admin_audit where request_id=?",correlation);org.slf4j.MDC.clear();ThreadLocalUtil.remove();
    }
    Snapshot add(){return editor.save(new Batch(park,1,editor.snapshot(park,1).revision(),List.of(new Create("a","A001",1,1,0),new Create("b","A002",1,2,0)),List.of(),List.of()));}
    @Test void batchKeepsIdsAndUpdatesCapacityCacheAndAudit(){
        assertTrue(floors.layout(park,1).isEmpty());Snapshot first=add();int id=first.items().get(0).id();
        assertEquals(2,floors.layout(park,1).size());
        var next=editor.save(new Batch(park,1,first.revision(),List.of(new Create("c","出口",2,1,4)),List.of(new Update(id,"道路",2)),List.of(first.items().get(1).id())));
        assertEquals(2,next.items().size());assertEquals(id,next.items().stream().filter(c->c.spaces_name().equals("道路")).findFirst().orElseThrow().id());
        assertEquals(0,db.queryForObject("select park_mount from parking_capacity_total where park_id=?",Integer.class,park));
        assertEquals(2,db.queryForObject("select count(*) from admin_audit where request_id=? and action='layout.batch'",Integer.class,correlation));
        assertTrue(floors.layout(park,1).stream().anyMatch(c->c.get("spaces_name").equals("道路")));
    }
    @Test void invalidChangesAndCoordinatesRollBackEverything(){
        var original=add();int id=original.items().get(0).id();
        assertThrows(IllegalArgumentException.class,()->editor.save(new Batch(park,1,original.revision(),List.of(new Create("x","覆盖",1,1,0)),List.of(new Update(id,"变化",2)),List.of())));
        assertEquals(original,editor.snapshot(park,1));
        assertThrows(IllegalArgumentException.class,()->editor.save(new Batch(park,1,original.revision(),List.of(new Create("x","越界",101,1,0)),List.of(),List.of())));
        assertThrows(IllegalArgumentException.class,()->editor.save(new Batch(park,1,original.revision(),List.of(),List.of(new Update(id,"重复",0)),List.of(id))));
        assertThrows(IllegalArgumentException.class,()->editor.save(new Batch(park,2,editor.snapshot(park,2).revision(),List.of(),List.of(new Update(id,"错层",0)),List.of())));
    }
    @Test void occupancyDoesNotInvalidateRevisionButCannotBeEdited(){
        var original=add();int id=original.items().get(0).id();var reservation=flow.reserve(user,id);
        assertEquals(original.revision(),editor.snapshot(park,1).revision());assertFalse(editor.snapshot(park,1).items().get(0).editable());
        var failure=assertThrows(Conflict.class,()->editor.save(new Batch(park,1,original.revision(),List.of(new Create("x","新位置",2,1,0)),List.of(),List.of(id))));
        assertEquals("OCCUPIED",failure.reason);assertEquals(2,editor.snapshot(park,1).items().size());
        flow.cancel(user,reservation.getId());editor.save(new Batch(park,1,original.revision(),List.of(),List.of(new Update(id,"更名",0)),List.of()));
    }
    @Test void oldEndpointsAndResponseLossCannotReplayCreates(){
        var start=editor.snapshot(park,1);var request=new Batch(park,1,start.revision(),List.of(new Create("x","A001",1,1,0)),List.of(),List.of());
        var saved=editor.save(request);assertThrows(Conflict.class,()->editor.save(request));assertEquals(saved,editor.snapshot(park,1));
        db.update("update spaces set spaces_name='外部修改' where park_id=?",park);
        assertThrows(Conflict.class,()->editor.save(new Batch(park,1,saved.revision(),List.of(new Create("y","A002",1,2,0)),List.of(),List.of())));
    }
    @Test void ownershipAndUserRoleAreCheckedOnEverySave(){
        var original=add();ThreadLocalUtil.set(Map.of("id",actor+100000,"permissions",0));assertThrows(IllegalArgumentException.class,()->editor.snapshot(park,1));
        ThreadLocalUtil.set(Map.of("id",user,"permissions",-1));assertThrows(IllegalArgumentException.class,()->editor.save(new Batch(park,1,original.revision(),List.of(),List.of(),List.of(original.items().get(0).id()))));
        ThreadLocalUtil.set(Map.of("id",actor,"permissions",0));assertEquals(2,editor.snapshot(park,1).items().size());
    }
    @Test void concurrentEditorsHaveExactlyOneWinner() throws Exception {
        var snapshot=add();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();for(int n=0;n<2;n++){final int i=n;results.add(pool.submit(()->{actor();start.await();try{editor.save(new Batch(park,1,snapshot.revision(),List.of(new Create("new"+i,"新增"+i,2,i+1,0)),List.of(),List.of()));return true;}catch(Conflict expected){return false;}finally{ThreadLocalUtil.remove();}}));}
            start.countDown();assertNotEquals(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));assertEquals(3,editor.snapshot(park,1).items().size());
        } finally {pool.shutdownNow();}
    }
    @Test void editingAndReservationSerializeWithoutLosingCapacity() throws Exception {
        var snapshot=add();int id=snapshot.items().get(0).id();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var edit=pool.submit(()->{actor();start.await();try{editor.save(new Batch(park,1,snapshot.revision(),List.of(),List.of(new Update(id,"道路",2)),List.of()));return true;}catch(Conflict expected){return false;}finally{ThreadLocalUtil.remove();}});
            var reserve=pool.submit(()->{start.await();try{flow.reserve(user,id);return true;}catch(IllegalStateException expected){return false;}});
            start.countDown();assertNotEquals(edit.get(10,TimeUnit.SECONDS),reserve.get(10,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("select park_spare from parking_capacity_total where park_id=?",Integer.class,park));
            assertEquals(db.queryForObject("select count(*) from spaces where park_id=? and spaces_flag in(0,1)",Integer.class,park),db.queryForObject("select park_mount from parking_capacity_total where park_id=?",Integer.class,park));
        } finally {pool.shutdownNow();}
    }
    @Test void cancellationAndEditingNeverReleaseAnotherSlot() throws Exception {
        var snapshot=add();int id=snapshot.items().get(0).id();var reservation=flow.reserve(user,id);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var edit=pool.submit(()->{actor();start.await();try{editor.save(new Batch(park,1,snapshot.revision(),List.of(),List.of(new Update(id,"更名",0)),List.of()));}catch(Conflict expected){assertEquals("OCCUPIED",expected.reason);}finally{ThreadLocalUtil.remove();}return null;});
            var cancel=pool.submit(()->{start.await();flow.cancel(user,reservation.getId());return null;});
            start.countDown();edit.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS);
            assertEquals(2,db.queryForObject("select park_spare from parking_capacity_total where park_id=?",Integer.class,park));
            assertTrue(editor.snapshot(park,1).items().stream().allMatch(Cell::editable));
        } finally {pool.shutdownNow();}
    }
    @Test void lateFailureRollsBackLayoutCapacityAndAuditIntent() {
        var before=add();int auditCount=db.queryForObject("select count(*) from admin_audit where request_id=?",Integer.class,correlation);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{
            editor.save(new Batch(park,1,before.revision(),List.of(new Create("x","新增",2,1,0)),List.of(),List.of()));
            throw new IllegalStateException("isolated rollback injection");
        }));
        assertEquals(before,editor.snapshot(park,1));assertEquals(2,floors.layout(park,1).size());
        assertEquals(2,db.queryForObject("select park_spare from parking_capacity_total where park_id=?",Integer.class,park));
        assertEquals(auditCount,db.queryForObject("select count(*) from admin_audit where request_id=?",Integer.class,correlation));
    }
    @Test void ownershipChangeIsRecheckedAfterWaitingForMetadataLock() throws Exception {
        var before=add();String name="owner"+UUID.randomUUID().toString().substring(0,8);
        db.update("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,'test',0,now(),now())",name);
        int nextOwner=db.queryForObject("select id from admin where admin_name=?",Integer.class,name);
        var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var transfer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                db.queryForList("select id from parking where id=? for update",park);locked.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                db.update("update parking set admin_id=? where id=?",nextOwner,park);return null;
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var edit=pool.submit(()->{ThreadLocalUtil.set(Map.of("id",actor,"permissions",0));try{return assertThrows(IllegalArgumentException.class,()->editor.save(new Batch(park,1,before.revision(),List.of(new Create("x","越权新增",2,1,0)),List.of(),List.of())));}finally{ThreadLocalUtil.remove();}});
            release.countDown();transfer.get(10,TimeUnit.SECONDS);assertTrue(edit.get(10,TimeUnit.SECONDS).getMessage().contains("无权"));assertEquals(before,editor.snapshot(park,1));
        } finally {release.countDown();pool.shutdownNow();db.update("update parking set admin_id=? where id=?",actor,park);db.update("delete from admin where id=?",nextOwner);}
    }
}
