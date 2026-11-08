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
    final AiTools tools=new AiTools(db,dashboard,Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneId.of("Asia/Shanghai")));
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
        verify(db,never()).queryForList(anyString(),any(Object[].class));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void membershipIsEvaluatedAtBusinessTimeForAuthenticatedUser(){
        call(new AiActor(7,-1),"myAccount","{}");
        var time=LocalDateTime.of(2026,11,9,8,0);
        verify(db).queryForList(contains("membership_active"),eq(time),eq(time),eq(7));
    }
}
