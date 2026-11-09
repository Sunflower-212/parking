package org.zjmproject.controller;

import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.zjmproject.ai.*;
import org.zjmproject.pojo.Result;

@RestController
@RequestMapping("/ai/sessions")
public class AiSessionController {
    private final JdbcTemplate db;
    private final AiSessions sessions;
    public AiSessionController(JdbcTemplate db,AiSessions sessions){this.db=db;this.sessions=sessions;}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> conflict(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Result.error(e.getReason()));}
    @GetMapping public Result<?> list() {
        var actor=AiActor.current();
        var records=db.query("select chatId,title,create_time,update_time from historicalSession where userId=? and type=? order by update_time desc,id desc limit 100",(rs,row)->{
            var created=rs.getTimestamp("create_time").toLocalDateTime();
            var updated=rs.getTimestamp("update_time").toLocalDateTime();
            String id=rs.getString("chatId"), title=rs.getString("title");
            return new HashMap<String,Object>(Map.of("chatId",id,"title",title==null||title.isBlank()?"历史会话 "+created.toLocalDate():title,
                "createdAt",created.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString(),
                "updatedAt",updated.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString()));
        },Integer.toString(actor.id()),actor.historyType());
        // Release the JDBC connection before taking the session lock used by writes.
        records.forEach(row->row.put("expired",!sessions.present(actor.key()+":"+row.get("chatId"))));
        return Result.success(records);
    }
    @DeleteMapping("/{chatId}") public Result<?> delete(@PathVariable String chatId) {
        if(!chatId.matches("[a-zA-Z0-9_-]{1,100}"))throw new IllegalArgumentException("会话ID无效");
        var actor=AiActor.current();
        sessions.delete(actor,chatId,()->{
            var owners=db.queryForList("select userId,type from historicalSession where chatId=?",chatId);
            if(!owners.isEmpty() && (!Integer.toString(actor.id()).equals(owners.get(0).get("userId")) || !actor.historyType().equals(owners.get(0).get("type"))))
                throw new IllegalArgumentException("无权访问此会话");
            db.update("delete from historicalSession where chatId=? and userId=? and type=?",chatId,Integer.toString(actor.id()),actor.historyType());
        });
        return Result.success();
    }
}
