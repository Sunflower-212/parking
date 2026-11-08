package org.zjmproject.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Raw protocol rounds are kept together: a tool call must never outlive its result. */
@Component
public class AiSessions {
    private record Round(List<ObjectNode> nodes, String startedAt, String completedAt) {}
    private record Session(List<Round> rounds, long touched, int bytes) {}
    private final Map<String,String> active = new HashMap<>();
    private final LinkedHashMap<String,Session> sessions = new LinkedHashMap<>(16, .75f, true);
    private final Clock clock;
    private final int budget;
    @org.springframework.beans.factory.annotation.Autowired
    public AiSessions(Clock clock) { this(clock, 16 * 1024 * 1024); }
    AiSessions(Clock clock, int budget) { this.clock=clock; this.budget=budget; }
    private void prune() {
        sessions.entrySet().removeIf(e -> e.getValue().touched < clock.millis()-1_800_000);
        int total=sessions.values().stream().mapToInt(Session::bytes).sum();
        var it=sessions.entrySet().iterator();
        while (it.hasNext() && (sessions.size()>100 || total>budget)) { total-=it.next().getValue().bytes; it.remove(); }
    }
    public synchronized List<ObjectNode> messages(String key) {
        prune(); var session=sessions.get(key);
        if(session==null) return List.of();
        return session.rounds.stream().flatMap(r -> r.nodes.stream()).map(ObjectNode::deepCopy).toList();
    }
    public synchronized boolean present(String key) { prune(); return sessions.containsKey(key); }
    public String now() { return java.time.Instant.ofEpochMilli(clock.millis()).toString(); }
    public synchronized void begin(AiActor actor, String chatId, Runnable admit) {
        if(active.containsKey(actor.key())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"当前账号已有AI请求，请等待或停止后重试");
        if(active.size() >= 2) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"系统助手繁忙，请稍后重试");
        admit.run(); active.put(actor.key(),chatId);
    }
    public synchronized void end(AiActor actor) { active.remove(actor.key()); }
    public synchronized void delete(AiActor actor,String chatId,Runnable deleteRecord) {
        if(chatId.equals(active.get(actor.key()))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请先停止生成再删除会话");
        deleteRecord.run(); sessions.remove(actor.key()+":"+chatId);
    }
    public synchronized void remove(String key) { sessions.remove(key); }
    public void append(String key,List<ObjectNode> round) { append(key,round,now()); }
    public synchronized void append(String key, List<ObjectNode> round, String startedAt) {
        prune(); var previous=sessions.get(key);
        var rounds=new ArrayList<Round>(previous==null?List.of():previous.rounds);
        rounds.add(new Round(round.stream().map(ObjectNode::deepCopy).toList(),startedAt,now()));
        while(rounds.size()>1 && (size(rounds)>256*1024 || rounds.stream().mapToInt(r -> r.nodes.size()).sum()>40)) rounds.remove(0);
        int size=size(rounds);
        if(size>256*1024) { sessions.remove(key); return; }
        sessions.put(key,new Session(rounds,clock.millis(),size)); prune();
    }
    private int size(List<Round> rounds) {
        return rounds.stream().flatMap(r -> r.nodes.stream()).mapToInt(n->n.toString().getBytes(StandardCharsets.UTF_8).length).sum();
    }
    public synchronized List<Map<String,String>> visible(String key) {
        prune(); var session=sessions.get(key); if(session==null)return List.of();
        return session.rounds.stream().flatMap(r -> r.nodes.stream()
            .filter(n -> Set.of("user","assistant").contains(n.path("role").asText()) && !n.path("content").asText("").isBlank())
            .map(n -> Map.of("role",n.path("role").asText(),"content",n.path("content").asText(),
                "timestamp",n.path("role").asText().equals("user")?r.startedAt:r.completedAt))).toList();
    }
}
