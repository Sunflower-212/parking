package org.zjmproject.ai;

import org.zjmproject.utils.ThreadLocalUtil;
import java.util.Map;
import java.util.function.Supplier;

public record AiActor(int id, int role) {
    public static AiActor current() {
        Map<String,Object> claims = ThreadLocalUtil.get();
        if (claims == null) throw new IllegalArgumentException("请先登录");
        int role = ((Number) claims.get("permissions")).intValue();
        if (role < -1 || role > 1) throw new IllegalArgumentException("权限不足");
        return new AiActor(((Number) claims.get("id")).intValue(), role);
    }
    public String key() { return role + ":" + id; }
    public String historyType() { return role == -1 ? "chat" : role == 0 ? "admin-chat" : "root-chat"; }
    public <T> T scoped(Supplier<T> action) {
        Object previous = ThreadLocalUtil.get();
        try {
            ThreadLocalUtil.set(Map.of("id", id, "permissions", role));
            return action.get();
        } finally {
            if (previous == null) ThreadLocalUtil.remove(); else ThreadLocalUtil.set(previous);
        }
    }
}
