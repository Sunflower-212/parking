package org.zjmproject.ai;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.zjmproject.controller.*;
import org.zjmproject.utils.ThreadLocalUtil;
import reactor.core.publisher.Flux;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiControllerTest {
    @Test void concurrentRequestsAreLimitedAndCancellationReleasesCapacity(){
        var client=mock(ChatClient.class);var prompt=mock(ChatClient.ChatClientRequestSpec.class,RETURNS_SELF);var stream=mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(prompt);when(prompt.stream()).thenReturn(stream);when(stream.content()).thenReturn(Flux.never());
        var db=mock(JdbcTemplate.class);when(db.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        var tools=mock(AiTools.class);when(tools.callbacks(any(),any())).thenReturn(new org.springframework.ai.model.function.FunctionCallback[0]);
        var c=new ChatController(client,db,new AiSessions(Clock.systemUTC()),tools,"fake");
        try {
            ThreadLocalUtil.set(Map.of("id",1,"permissions",-1));var first=c.chat("one","a").subscribe();
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->c.chat("two","b"));
            ThreadLocalUtil.set(Map.of("id",1,"permissions",0));var second=c.chat("one","c").subscribe();
            ThreadLocalUtil.set(Map.of("id",2,"permissions",-1));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->c.chat("one","d"));
            first.dispose();var next=c.chat("one","d").subscribe();next.dispose();second.dispose();
        }finally{ThreadLocalUtil.remove();}
    }
    @Test void historyChecksRoleAndOwnerBeforeReadingMemory(){
        var db=mock(JdbcTemplate.class);var sessions=mock(AiSessions.class);var c=new ChatHistoryController(db,sessions);
        when(db.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        ThreadLocalUtil.set(Map.of("id",7,"permissions",0));
        try {assertThrows(IllegalArgumentException.class,()->c.history("chat","legacy-user-chat",new MockHttpServletResponse()));verifyNoInteractions(sessions);verify(db).queryForObject(anyString(),eq(Integer.class),eq("legacy-user-chat"),eq("admin-chat"),eq("7"));}
        finally{ThreadLocalUtil.remove();}
    }
}
