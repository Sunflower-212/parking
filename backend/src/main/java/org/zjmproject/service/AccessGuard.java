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
        if(role!=0) throw new IllegalArgumentException("无权操作此停车场");
        // A locking read observes a concurrent ownership transfer even when this
        // transaction has already established an older repeatable-read snapshot.
        // 事务内采用当前锁定读，避免可重复读旧快照在归属转移后仍授权原管理员。
        String lock=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()?" for update":"";
        var owners=db.queryForList("select admin_id from parking where id=?"+lock,Integer.class,id);
        if(owners.size()!=1 || owners.get(0)!=((Number)actor.get("id")).intValue())
            throw new IllegalArgumentException("无权操作此停车场");
    }
}
