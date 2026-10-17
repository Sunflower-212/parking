package org.zjmproject.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.zjmproject.utils.ThreadLocalUtil;
import java.util.Map;

@Component
public class AccessGuard {
    private final JdbcTemplate db;
    public AccessGuard(JdbcTemplate db) {this.db=db;}
    public static int userId() {
        Map<String,Object> actor=ThreadLocalUtil.get();
        if(actor==null || ((Number)actor.get("permissions")).intValue()!=-1) throw new IllegalArgumentException("仅普通用户可执行此操作");
        return ((Number)actor.get("id")).intValue();
    }
    public void parking(int id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        if(actor==null) throw new IllegalArgumentException("请先登录");
        int role=((Number)actor.get("permissions")).intValue();
        if(role==1) return;
        if(role!=0 || db.queryForObject("select count(*) from parking where id=? and admin_id=?",Integer.class,id,actor.get("id"))!=1)
            throw new IllegalArgumentException("无权操作此停车场");
    }
}
