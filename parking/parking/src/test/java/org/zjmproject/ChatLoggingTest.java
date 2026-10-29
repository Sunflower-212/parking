package org.zjmproject;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.zjmproject.controller.ChatController;
import org.zjmproject.service.*;
import org.zjmproject.utils.ThreadLocalUtil;
import reactor.core.publisher.Flux;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatLoggingTest {
    @Test void streamCompletionCancellationAndTimeoutKeepOnlySafeCorrelatedMetadata() {
        var client=mock(ChatClient.class);
        var prompt=mock(ChatClient.ChatClientRequestSpec.class,RETURNS_SELF);
        var stream=mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(prompt);when(prompt.stream()).thenReturn(stream);
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        var controller=new ChatController(client,db,mock(UserService.class),mock(ParkingService.class),mock(SpacesService.class),mock(ParkingFlowService.class));
        var logger=(Logger)LoggerFactory.getLogger(ChatController.class);
        var output=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event){event.prepareForDeferredProcessing();super.append(event);}
        };
        output.start();logger.addAppender(output);
        ThreadLocalUtil.set(Map.of("id",7,"permissions",-1));
        try {
            for(String expected:new String[]{"SUCCESS","CANCELLED","TIMEOUT"}) {
                Flux<String> source=switch(expected) {
                    case "SUCCESS" -> Flux.just("private AI response");
                    case "TIMEOUT" -> Flux.error(new java.util.concurrent.TimeoutException("private provider error"));
                    default -> Flux.never();
                };
                when(stream.content()).thenReturn(source);
                MDC.put("requestId","stream-origin");
                Flux<String> result=controller.chat("private user prompt","test-chat");
                MDC.put("requestId","worker-context");
                if(expected.equals("CANCELLED"))result.subscribe().dispose();
                else result.onErrorComplete().blockLast();
                assertEquals("worker-context",MDC.get("requestId"));
                var event=output.list.get(output.list.size()-1);
                assertEquals("stream-origin",event.getMDCPropertyMap().get("requestId"));
                assertTrue(event.getKeyValuePairs().stream().anyMatch(pair->pair.key.equals("result") && expected.equals(pair.value)));
                assertFalse(event.getFormattedMessage().contains("private"));
                assertFalse(event.getKeyValuePairs().toString().contains("private"));assertNull(event.getThrowableProxy());
            }
            assertEquals(3,output.list.size());
        } finally {logger.detachAppender(output);ThreadLocalUtil.remove();MDC.clear();}
    }
}
