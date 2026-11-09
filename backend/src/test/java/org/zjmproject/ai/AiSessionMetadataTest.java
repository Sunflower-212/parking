package org.zjmproject.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.zjmproject.controller.AiSessionController;
import org.zjmproject.utils.ThreadLocalUtil;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiSessionMetadataTest {
    @Test void titlesRemoveOnlyLeadingBoilerplateAndRespectUnicode() {
        assertEquals("最近7天的经营情况",AiSessionTitles.from("  你好，请帮我  最近7天的经营情况  "));
        assertEquals("你好",AiSessionTitles.from("你好"));
        assertEquals(25,AiSessionTitles.from("🚗".repeat(30)).codePointCount(0,AiSessionTitles.from("🚗".repeat(30)).length()));
        assertEquals("哪里可以查询余额",AiSessionTitles.from("请问：哪里可以查询余额"));
    }
    @Test void timestampsAreVisibleButNeverSentToTheModel() {
        var store=new AiSessions(Clock.fixed(Instant.parse("2026-11-09T02:01:00Z"),ZoneOffset.UTC));
        var json=new ObjectMapper();
        store.append("-1:7:x",List.of(json.createObjectNode().put("role","user").put("content","问题"),json.createObjectNode().put("role","assistant").put("content","回答").put("reasoning_content","private")),"2026-11-09T02:00:00Z");
        assertEquals("2026-11-09T02:00:00Z",store.visible("-1:7:x").get(0).get("timestamp"));
        assertEquals("2026-11-09T02:01:00Z",store.visible("-1:7:x").get(1).get("timestamp"));
        assertFalse(store.messages("-1:7:x").get(0).has("timestamp"));
        assertFalse(store.visible("-1:7:x").get(1).containsKey("reasoning_content"));
    }
    @Test void deletingAnActiveSessionIsRejectedAndFailedDeletionKeepsMemory() {
        var store=new AiSessions(Clock.systemUTC());var actor=new AiActor(7,-1);var json=new ObjectMapper();
        store.append("-1:7:x",List.of(json.createObjectNode().put("role","user").put("content","question")));
        store.begin(actor,"x",()->{});
        var deletion=mock(Runnable.class);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->store.delete(actor,"x",deletion)).getStatusCode().value());
        verifyNoInteractions(deletion);
        store.end(actor);
        assertThrows(IllegalStateException.class,()->store.delete(actor,"x",()->{throw new IllegalStateException();}));
        assertTrue(store.present("-1:7:x"));
        store.delete(actor,"x",deletion);store.delete(actor,"x",deletion);
        assertFalse(store.present("-1:7:x"));
        verify(deletion,times(2)).run();
    }
    @Test void failedAdmissionDoesNotConsumeCapacityAndRolesRemainSeparate() {
        var store=new AiSessions(Clock.systemUTC());var user=new AiActor(7,-1);var admin=new AiActor(7,0);
        assertThrows(IllegalArgumentException.class,()->store.begin(user,"bad",()->{throw new IllegalArgumentException();}));
        store.begin(user,"user",()->{});store.begin(admin,"admin",()->{});
        assertThrows(ResponseStatusException.class,()->store.begin(new AiActor(8,-1),"other",()->fail("must not create a record")));
        store.delete(admin,"user",()->{}); // Different role namespace.
        assertThrows(ResponseStatusException.class,()->store.delete(user,"user",()->{}));
        store.end(user);store.end(admin);
    }
    @Test void deletionRejectsCrossRoleOwnerAndIsIdempotentForAbsentRecords() {
        var db=mock(JdbcTemplate.class);var store=new AiSessions(Clock.systemUTC());var controller=new AiSessionController(db,store);
        ThreadLocalUtil.set(Map.of("id",7,"permissions",0));
        try {
            when(db.queryForList(anyString(),eq("user-chat"))).thenReturn(List.of(Map.of("userId","7","type","chat")));
            assertThrows(IllegalArgumentException.class,()->controller.delete("user-chat"));
            verify(db,never()).update(anyString(),any(Object[].class));
            when(db.queryForList(anyString(),eq("absent"))).thenReturn(List.of());
            assertEquals(0,controller.delete("absent").getCode());
            assertEquals(0,controller.delete("absent").getCode());
        } finally {ThreadLocalUtil.remove();}
    }
}
