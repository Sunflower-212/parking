package org.zjmproject.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekThinkingTest {
    final ObjectMapper json=new ObjectMapper();
    String chunk(Map<String,Object> delta,String finish)throws Exception{return "data: "+json.writeValueAsString(Map.of("choices",List.of(Map.of("delta",delta,"finish_reason",finish))))+"\n\n";}
    @Test void repeatedUnknownToolsStopAtSixWithoutSavingPartialHistory()throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try{
            requests.add(json.readTree(exchange.getRequestBody()));
            String reply=chunk(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-"+requests.size(),"function",Map.of("name","unknown","arguments","{}")))),"tool_calls");
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(reply.getBytes(StandardCharsets.UTF_8));exchange.close();
        }catch(Exception e){exchange.close();}});server.start();
        try{
            var sessions=new AiSessions(Clock.systemUTC());var model=new DeepSeekThinkingChatModel(json,"http://127.0.0.1:"+server.getAddress().getPort(),"fake","deepseek-flash",sessions);
            var run=new DeepSeekThinkingChatModel.Run("test",List.of(),e->{});
            var error=assertThrows(DeepSeekThinkingChatModel.Failure.class,()->ChatClient.builder(model).build().prompt().user("test").toolContext(Map.of(DeepSeekThinkingChatModel.RUN,run)).stream().content().blockLast(Duration.ofSeconds(10)));
            assertEquals("TOOL_LIMIT",error.code);assertEquals(7,requests.size());assertTrue(requests.get(1).toString().contains("UNKNOWN_TOOL"));assertFalse(sessions.present("test"));
        }finally{server.stop(0);}
    }
    @Test void realChatClientRetainsReasoningAcrossFragmentedToolsAndNextUserTurn()throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try {
            requests.add(json.readTree(exchange.getRequestBody()));int round=requests.size();
            String reply;
            if(round==1)reply=chunk(Map.of("reasoning_content","private thought"),"")+chunk(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-1","function",Map.of("name","lookup","arguments","{\"x\":")))),"")+chunk(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments","1}")))),"tool_calls");
            else reply=chunk(Map.of("reasoning_content","retained thought"),"")+chunk(Map.of("content","回答"),"stop");
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            // Byte fragmentation includes Chinese UTF-8 boundaries.
            for(byte b:(reply+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)){exchange.getResponseBody().write(b);}
            exchange.close();
        }catch(Exception e){exchange.close();}});server.start();
        try {
            var sessions=new AiSessions(Clock.systemUTC());var model=new DeepSeekThinkingChatModel(json,"http://127.0.0.1:"+server.getAddress().getPort(),"fake","deepseek-flash",sessions);var client=ChatClient.builder(model).build();
            var fn=new FunctionCallback(){public String getName(){return "lookup";}public String getDescription(){return "test";}public String getInputTypeSchema(){return "{\"type\":\"object\"}";}public String call(String raw){assertEquals("{\"x\":1}",raw);return "{\"ok\":true}";}};
            for(int i=0;i<2;i++) {
                var run=new DeepSeekThinkingChatModel.Run("-1:7:c",sessions.messages("-1:7:c"),e->{});
                var answer=client.prompt().user("question").toolContext(Map.of(DeepSeekThinkingChatModel.RUN,run)).functions(fn).stream().content().collectList().block(Duration.ofSeconds(10));
                assertEquals("回答",String.join("",answer));
            }
            assertEquals(3,requests.size());assertEquals("enabled",requests.get(0).path("thinking").path("type").asText());
            assertEquals("private thought",requests.get(1).path("messages").get(2).path("reasoning_content").asText());
            assertEquals("call-1",requests.get(1).path("messages").get(3).path("tool_call_id").asText());
            assertTrue(requests.get(2).toString().contains("retained thought"));
            assertFalse(sessions.visible("-1:7:c").toString().contains("thought"));
        } finally {server.stop(0);}
    }
    @Test void nextTurnQueriesFreshEntryStateInsteadOfRepeatingOldReservation()throws Exception {
        var fixture=new AiToolsTest();var row=fixture.entry(0,1);fixture.state(row);
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try {
            var body=json.readTree(exchange.getRequestBody());requests.add(body);
            String reply;
            if(requests.size()%2==1)reply=chunk(Map.of("tool_calls",List.of(Map.of("index",0,"id","current-"+requests.size(),"function",Map.of("name","myReservation","arguments","{}")))),"tool_calls");
            else {
                var messages=body.path("messages");var tool=json.readTree(messages.get(messages.size()-1).path("content").asText());
                reply=chunk(Map.of("content",tool.path("data").path("message").asText()),"stop");
            }
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write(reply.getBytes(StandardCharsets.UTF_8));exchange.close();
        }catch(Exception e){exchange.close();}});server.start();
        try {
            var sessions=new AiSessions(fixture.clock);var model=new DeepSeekThinkingChatModel(json,"http://127.0.0.1:"+server.getAddress().getPort(),"fake","deepseek-flash",sessions);
            var client=ChatClient.builder(model).build();
            for(int turn=0;turn<2;turn++) {
                if(turn==1){row.put("flag",1);row.put("expires_at",java.sql.Timestamp.valueOf(LocalDateTime.now(fixture.clock).minusDays(4)));}
                var run=new DeepSeekThinkingChatModel.Run("-1:7:state",sessions.messages("-1:7:state"),e->{});
                var parts=client.prompt().user(turn==0?"我的预约":"取消预约").toolContext(Map.of(DeepSeekThinkingChatModel.RUN,run)).functions(fixture.tools.callbacks(new AiActor(7,-1),e->{})).stream().content().collectList().block(Duration.ofSeconds(10));
                assertTrue(String.join("",parts).contains(turn==0?"已有有效预约":"车辆已入场，不能再次预约或取消预约"));
            }
            assertEquals(4,requests.size());
            assertTrue(requests.get(2).toString().contains("已有有效预约"));
            var messages=requests.get(3).path("messages");var current=json.readTree(messages.get(messages.size()-1).path("content").asText()).path("data");
            assertEquals("IN_PARK",current.path("status").asText());assertFalse(current.has("expiresAt"));
            assertTrue(requests.get(0).path("messages").get(0).path("content").asText().contains("myReservation查询最新状态"));
        } finally {server.stop(0);}
    }
}
