package org.zjmproject.service;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import java.util.*;
public class BoundedChatMemory implements ChatMemory {
    private record Conversation(List<Message> messages,long touched) {}
    // 按访问顺序维护会话；30 分钟未写入、超过 1000 个会话或单会话超过 40 条时裁剪内存。
    private final LinkedHashMap<String,Conversation> conversations=new LinkedHashMap<>(128,0.75f,true);
    private void prune(){long cutoff=System.currentTimeMillis()-1800000;conversations.entrySet().removeIf(e->e.getValue().touched()<cutoff);while(conversations.size()>1000) conversations.remove(conversations.keySet().iterator().next());}
    @Override public synchronized void add(String id,List<Message> messages){prune();var old=conversations.get(id);var all=new ArrayList<Message>(old==null?List.of():old.messages());all.addAll(messages);if(all.size()>40)all=new ArrayList<>(all.subList(all.size()-40,all.size()));conversations.put(id,new Conversation(all,System.currentTimeMillis()));prune();}
    // 访问顺序 Map 的读取也会修改内部链表，因此与写入共用同步锁，并返回不可变副本。
    @Override public synchronized List<Message> get(String id,int lastN){prune();var c=conversations.get(id);if(c==null)return List.of();return List.copyOf(c.messages().subList(Math.max(0,c.messages().size()-Math.max(0,lastN)),c.messages().size()));}
    @Override public synchronized void clear(String id){conversations.remove(id);}
}
