package org.zjmproject.controller;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.zjmproject.ai.*;
import org.zjmproject.observability.RuntimeEvents;
import reactor.core.publisher.*;
import java.util.*;
@RestController @RequestMapping("/ai")
@ConditionalOnProperty(name="parking.integrations.ai",havingValue="true")
public class ChatController {
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<org.zjmproject.pojo.Result<?>> busy(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(org.zjmproject.pojo.Result.error(e.getReason()));}
    private final ChatClient client;private final JdbcTemplate db;private final AiSessions sessions;private final AiTools tools;
    private final String apiKey;
    public ChatController(ChatClient client,JdbcTemplate db,AiSessions sessions,AiTools tools,@Value("${spring.ai.openai.api-key:}") String apiKey){this.client=client;this.db=db;this.sessions=sessions;this.tools=tools;this.apiKey=apiKey;}
    public record Input(String chatId,String message) {}
    @PostMapping(value="/chat",produces="text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<Map<String,Object>>> post(@RequestBody Input input){return conversation(input).map(event->ServerSentEvent.<Map<String,Object>>builder(event).event(event.get("type").toString()).build());}
    @GetMapping(value="/chat",produces="text/plain;charset=UTF-8")
    public Flux<String> chat(@RequestParam String message,@RequestParam String chatId){return conversation(new Input(chatId,message)).filter(e->Set.of("delta","error").contains(e.get("type"))).map(e->e.getOrDefault("text",e.getOrDefault("message","")).toString());}
    private Flux<Map<String,Object>> conversation(Input input) {
        AiActor actor=AiActor.current();String requestId=RuntimeEvents.requestId();if(requestId==null)requestId=UUID.randomUUID().toString();final String correlation=requestId;
        if(input==null||input.chatId()==null||!input.chatId().matches("[a-zA-Z0-9_-]{1,100}")||input.message()==null||input.message().isBlank()||input.message().length()>8000)throw new IllegalArgumentException("会话ID或消息长度无效");
        if(apiKey.isBlank())throw new IllegalArgumentException("AI服务尚未配置凭据，请联系管理员");
        var created=new java.util.concurrent.atomic.AtomicBoolean();
        sessions.begin(actor,input.chatId(),()->{
            created.set(db.queryForObject("select count(*) from historicalSession where chatId=?",Integer.class,input.chatId())==0);
            db.update("insert into historicalSession(chatId,type,userId,title,create_time,update_time) values(?,?,?,?,now(),now()) on duplicate key update chatId=chatId",input.chatId(),actor.historyType(),Integer.toString(actor.id()),AiSessionTitles.from(input.message()));
            if(db.queryForObject("select count(*) from historicalSession where chatId=? and userId=? and type=?",Integer.class,input.chatId(),Integer.toString(actor.id()),actor.historyType())!=1)throw new IllegalArgumentException("无权访问此会话");
        });
        long started=System.nanoTime();var completed=new java.util.concurrent.atomic.AtomicBoolean();
        var finished=Sinks.one();
        var events=Sinks.many().unicast().<Map<String,Object>>onBackpressureBuffer(new java.util.concurrent.ArrayBlockingQueue<>(32));
        java.util.function.Consumer<Map<String,Object>> emit=event->{if(events.tryEmitNext(event).isFailure())throw new DeepSeekThinkingChatModel.Failure("CLIENT_SLOW","接收速度过慢，请重新提问");};
        String key=actor.key()+":"+input.chatId();var run=new DeepSeekThinkingChatModel.Run(key,sessions.messages(key),emit);run.startedAt=sessions.now();
        emit.accept(Map.of("type","message_time","role","user","timestamp",run.startedAt));
        Flux<Map<String,Object>> answer;
        try {answer=client.prompt().user(input.message()).toolContext(Map.of(DeepSeekThinkingChatModel.RUN,run)).functions(tools.callbacks(actor,emit)).stream().content().map(text->Map.<String,Object>of("type","delta","text",text)).doOnComplete(()->{db.update("update historicalSession set update_time=now() where chatId=? and userId=? and type=?",input.chatId(),Integer.toString(actor.id()),actor.historyType());completed.set(true);emit.accept(Map.of("type","message_time","role","assistant","timestamp",sessions.now()));}).doFinally(s->{events.tryEmitComplete();finished.tryEmitEmpty();});}
        catch(RuntimeException e){finish(actor,input.chatId(),key,created.get(),false);throw e;}
        // Writes detect a closed browser even while the provider is silent, cancelling upstream work.
        var heartbeat=Flux.interval(java.time.Duration.ofSeconds(2)).map(t->Map.<String,Object>of("type","heartbeat")).takeUntilOther(finished.asMono());
        return Flux.merge(events.asFlux(),heartbeat,answer).onErrorResume(e->{String code="UNAVAILABLE",message="AI服务暂不可用，请稍后重试";if(e instanceof DeepSeekThinkingChatModel.Failure f){code=f.code;message=f.getMessage();}else if(e instanceof java.util.concurrent.TimeoutException){code="TIMEOUT";message="AI响应超时，请稍后重试";}return Flux.just(Map.of("type","error","code",code,"message",message,"requestId",correlation));})
            .concatWith(Mono.fromSupplier(()->Map.of("type","done","success",completed.get())))
            .doFinally(signal->{finish(actor,input.chatId(),key,created.get(),completed.get());try(var context=RuntimeEvents.scope(correlation)){
                org.slf4j.LoggerFactory.getLogger(ChatController.class).atInfo().addKeyValue("event","ai.stream").addKeyValue("actorId",actor.id()).addKeyValue("actorRole",actor.role()).addKeyValue("elapsedMs",(System.nanoTime()-started)/1_000_000).addKeyValue("result",signal==SignalType.CANCEL?"CANCELLED":completed.get()?"SUCCESS":"ERROR").log("stream_finished");
            }});
    }
    private void finish(AiActor actor,String chatId,String key,boolean created,boolean completed) {
        synchronized(sessions) {
            try { if(created && !completed) { db.update("delete from historicalSession where chatId=? and userId=? and type=?",chatId,Integer.toString(actor.id()),actor.historyType());sessions.remove(key); } }
            finally { sessions.end(actor); }
        }
    }

}
