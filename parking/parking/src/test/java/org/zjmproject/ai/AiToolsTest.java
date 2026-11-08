package org.zjmproject.ai;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.zjmproject.service.DashboardService;
import org.zjmproject.utils.ThreadLocalUtil;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiToolsTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final DashboardService dashboard=mock(DashboardService.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneId.of("Asia/Shanghai"));
    final AiTools tools=new AiTools(db,dashboard,clock);
    String call(AiActor actor,String name,String args){return Arrays.stream(tools.callbacks(actor,e->{})).filter(f->f.getName().equals(name)).findFirst().orElseThrow().call(args);}
    @Test void malformedArgumentsNeverReachDatabase(){
        for(String input:List.of("{}","[]","null","{","{\"parkingId\":1,\"floor\":0}","{\"parkingId\":1,\"floor\":101}","{\"parkingId\":\"1\",\"floor\":1}","{\"parkingId\":1.0,\"floor\":1}","{\"parkingId\":null,\"floor\":1}","{\"parkingId\":1,\"floor\":1,\"userId\":2}","{\"parkingId\":1,\"parkingId\":2,\"floor\":1}","{\"parkingId\":1,\"floor\":1} {}"," ".repeat(8193)))
            assertTrue(call(new AiActor(7,-1),"findSpaces",input).contains("INVALID_ARGUMENT"),input);
        verifyNoInteractions(db,dashboard);
    }
    @Test void roleListsAndDateBoundsAreEnforced(){
        assertFalse(tools.capabilities(new AiActor(7,0)).contains("prepareCancellation"));
        assertFalse(tools.capabilities(new AiActor(7,-1)).contains("managementSummary"));
        assertTrue(call(new AiActor(7,-1),"myOrders","{\"from\":\"2026-01-01T00:00:00\",\"to\":\"2026-11-01T00:00:00\"}").contains("INVALID_ARGUMENT"));
        assertTrue(call(new AiActor(7,-1),"myOrders","{\"from\":\"invalid\"}").contains("INVALID_ARGUMENT"));verifyNoInteractions(db,dashboard);
    }
    @Test void adminScopeIsInSqlAndThreadContextIsRestored(){
        var previous=Map.of("id",99,"permissions",1);ThreadLocalUtil.set(previous);
        call(new AiActor(7,0),"findParking","{\"keyword\":\"A\"}");
        verify(db).queryForList(contains("p.admin_id=?"),eq("%A%"),eq("%A%"),eq(7),eq(0));
        assertSame(previous,ThreadLocalUtil.get());ThreadLocalUtil.remove();
    }
    @Test void privateIdentityIsCapturedAndCancelMustBelongToActor(){
        assertTrue(call(new AiActor(7,-1),"prepareCancellation","{\"reservationId\":123}").contains("NOT_FOUND_OR_FORBIDDEN"));
        verify(db).queryForList(contains("e.active_user_id=?"),eq(7));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void databaseErrorsCannotLeakSql(){
        when(db.queryForList(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private jdbc:secret"));
        String result=call(new AiActor(7,-1),"myAccount","{}");assertTrue(result.contains("UNAVAILABLE"));assertFalse(result.contains("secret"));
    }
    @Test void invalidPlateCannotCreateActionCard(){
        when(db.queryForObject(anyString(),eq(String.class),eq(7))).thenReturn(null);
        assertTrue(call(new AiActor(7,-1),"prepareReservation","{\"spaceId\":1}").contains("PROFILE_REQUIRED"));
        verify(db).queryForList(contains("e.active_user_id=?"),eq(7));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void membershipIsEvaluatedAtBusinessTimeForAuthenticatedUser(){
        call(new AiActor(7,-1),"myAccount","{}");
        var time=LocalDateTime.of(2026,11,9,8,0);
        verify(db).queryForList(contains("membership_active"),eq(time),eq(time),eq(7));
    }
    Map<String,Object> entry(int flag,int reservation) {
        var row=new HashMap<String,Object>();
        row.putAll(Map.of("id",11,"park_id",2,"space_id",3,"linked_park_id",2,"linked_space_id",3,"space_park_id",2,"active_enter_id",11,"spaces_flag",1));
        row.putAll(Map.of("park_name","test","spaces_name","A412","spaces_storey",1,"flag",flag,"reservation",reservation));
        row.put("enter_time",java.sql.Timestamp.valueOf(LocalDateTime.now(clock).minusDays(4)));
        row.put("expires_at",java.sql.Timestamp.valueOf(LocalDateTime.now(clock).plusMinutes(30)));
        return row;
    }
    com.fasterxml.jackson.databind.JsonNode state(Map<String,Object> row)throws Exception {
        when(db.queryForList(contains("e.active_user_id=?"),eq(7))).thenReturn(row==null?List.of():List.of(row));
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(call(new AiActor(7,-1),"myReservation","{}")).path("data");
    }
    @Test void entryWinsOverBothReservationOriginAndOldDeadline()throws Exception {
        for(int reservation:List.of(0,1)) {
            var row=entry(1,reservation);row.put("expires_at",java.sql.Timestamp.valueOf(LocalDateTime.now(clock).minusDays(4)));
            var result=state(row);
            assertEquals("IN_PARK",result.path("status").asText());assertFalse(result.path("canReserve").asBoolean());assertFalse(result.path("canCancel").asBoolean());
            assertTrue(result.hasNonNull("enteredAt"));assertFalse(result.has("expiresAt"));assertFalse(result.has("reservation"));
            var events=new ArrayList<Map<String,Object>>();
            for(var callback:tools.callbacks(new AiActor(7,-1),events::add)) {
                if(callback.getName().equals("prepareReservation"))assertTrue(callback.call("{\"spaceId\":4}").contains("IN_PARK"));
                if(callback.getName().equals("prepareCancellation"))assertTrue(callback.call("{\"reservationId\":11}").contains("IN_PARK"));
            }
            assertTrue(events.stream().noneMatch(e->"action".equals(e.get("type"))));
            assertTrue(call(new AiActor(7,-1),"prepareCancellation","{\"reservationId\":999}").contains("NOT_FOUND_OR_FORBIDDEN"));
        }
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void reservationExpiryAndNoRecordHaveExplicitCapabilities()throws Exception {
        var row=entry(0,1);var valid=state(row);
        assertEquals("RESERVED",valid.path("status").asText());assertTrue(valid.path("canCancel").asBoolean());assertFalse(valid.has("enteredAt"));
        row.put("expires_at",java.sql.Timestamp.valueOf(LocalDateTime.now(clock)));
        var expired=state(row);assertEquals("EXPIRED_PENDING_RELEASE",expired.path("status").asText());
        assertFalse(expired.path("canReserve").asBoolean());assertTrue(expired.path("canCancel").asBoolean());
        assertTrue(call(new AiActor(7,-1),"prepareCancellation","{\"reservationId\":11}").contains("AWAITING_CONFIRMATION"));
        var none=state(null);assertEquals("NONE",none.path("status").asText());assertTrue(none.path("canReserve").asBoolean());assertFalse(none.path("canCancel").asBoolean());
    }
    @Test void missingAssociationsAndTimesAreNeverTreatedAsFree()throws Exception {
        for(String field:List.of("linked_park_id","linked_space_id","active_enter_id","expires_at")) {
            var row=entry(0,1);row.remove(field);var result=state(row);
            assertEquals("UNKNOWN",result.path("status").asText(),field);assertFalse(result.path("canReserve").asBoolean());assertFalse(result.path("canCancel").asBoolean());
            assertTrue(call(new AiActor(7,-1),"prepareReservation","{\"spaceId\":4}").contains("UNKNOWN"));
            assertTrue(call(new AiActor(7,-1),"prepareCancellation","{\"reservationId\":11}").contains("UNKNOWN"));
        }
        var entered=entry(1,1);entered.remove("enter_time");assertEquals("UNKNOWN",state(entered).path("status").asText());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
