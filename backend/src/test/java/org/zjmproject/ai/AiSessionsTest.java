package org.zjmproject.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AiSessionsTest {
    @Test void expiredSessionsAreNotReturned(){
        var clock=org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenReturn(0L);
        var store=new AiSessions(clock);var json=new ObjectMapper();
        store.append("-1:1:a",List.of(json.createObjectNode().put("role","user").put("content","test")));
        assertTrue(store.present("-1:1:a"));
        org.mockito.Mockito.when(clock.millis()).thenReturn(1_800_001L);
        assertFalse(store.present("-1:1:a"));assertTrue(store.messages("-1:1:a").isEmpty());
    }
    @Test void roundsAreAtomicBoundedAndRolesAreIsolated(){
        var store=new AiSessions(Clock.systemUTC(),5000);var json=new ObjectMapper();
        for(int i=0;i<25;i++)store.append("-1:7:a",List.of(json.createObjectNode().put("role","assistant").put("reasoning_content","private").put("tool_calls",i),json.createObjectNode().put("role","tool").put("content","result")));
        var raw=store.messages("-1:7:a");assertTrue(raw.size()<=40);assertEquals(0,raw.size()%2);assertTrue(store.messages("0:7:a").isEmpty());assertTrue(store.visible("-1:7:a").isEmpty());
        raw.get(0).put("tampered",true);assertFalse(store.messages("-1:7:a").get(0).has("tampered"));
    }
    @Test void budgetEvictsOldSessionsAndHugeTurnsAreNotRetained(){
        var store=new AiSessions(Clock.systemUTC(),300);var json=new ObjectMapper();
        for(int i=0;i<110;i++)store.append("-1:7:"+i,List.of(json.createObjectNode().put("role","user").put("content","x".repeat(30))));
        assertFalse(store.present("-1:7:0"));assertTrue(store.present("-1:7:109"));
        store.append("huge",List.of(json.createObjectNode().put("content","x".repeat(300000))));assertFalse(store.present("huge"));
    }
}
