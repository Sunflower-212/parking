package org.zjmproject.controller;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.zjmproject.ai.*;
import java.util.*;
@RestController @RequestMapping("/ai/history")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="parking.integrations.ai",havingValue="true")
public class ChatHistoryController {
    private final JdbcTemplate db;private final AiSessions sessions;
    public ChatHistoryController(JdbcTemplate db,AiSessions sessions){this.db=db;this.sessions=sessions;}
    @GetMapping("/{type}") public List<String> list(@PathVariable String type) {
        if(!type.equals("chat"))throw new IllegalArgumentException("会话类型无效");var a=AiActor.current();
        return db.queryForList("select chatId from historicalSession where userId=? and type=? order by id desc limit 100",String.class,Integer.toString(a.id()),a.historyType());
    }
    @GetMapping("/{type}/{chatId}") public List<Map<String,String>> history(@PathVariable String type,@PathVariable String chatId,HttpServletResponse response) {
        if(!type.equals("chat"))throw new IllegalArgumentException("会话类型无效");var a=AiActor.current();
        if(db.queryForObject("select count(*) from historicalSession where chatId=? and type=? and userId=?",Integer.class,chatId,a.historyType(),Integer.toString(a.id()))!=1)throw new IllegalArgumentException("无权访问此会话");
        String key=a.key()+":"+chatId;response.setHeader("X-AI-History-Expired",Boolean.toString(!sessions.present(key)));return sessions.visible(key);
    }
}
