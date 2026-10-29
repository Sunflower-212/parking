package org.zjmproject.interceptors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.zjmproject.utils.JwtUtil;
import org.zjmproject.utils.ThreadLocalUtil;

import java.util.Map;
import java.util.Set;

@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    private static final Set<String> PUBLIC = Set.of("/user/login", "/user/register", "/admin/login", "/user/rechargeNotify", "/actuator/health", "/error");
    private static final Set<String> USER_ADMIN = Set.of("/user/pageList", "/user/addUser", "/user/delete", "/user/resetPassword", "/user/data", "/user/export");
    private static final Set<String> ENTER_USER = Set.of("/enter/reservation", "/enter/cancelReservation", "/enter/userEnterOrReservation", "/enter/userPageList");
    private static final Set<String> ADMIN_SELF = Set.of("/admin/adminInfo", "/admin/updatePassword", "/admin/logout");
    private final StringRedisTemplate redis;

    public AuthenticationInterceptor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        ThreadLocalUtil.remove();
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (req.getMethod().equals("OPTIONS") || PUBLIC.contains(path)) return true;
        String token = req.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        Map<String, Object> claims;
        try {
            claims = JwtUtil.parseToken(token);
            int role = ((Number) claims.get("permissions")).intValue();
            if (role < -1 || role > 1 || !(claims.get("id") instanceof Number)) throw new IllegalArgumentException();
            String identity = (String) claims.get(role == -1 ? "username" : "adminname");
            if (identity == null || !token.equals(redis.opsForValue().get((role == -1 ? "user=" : "admin=") + identity)))
                throw new IllegalArgumentException();
        // 登录状态 Redis 不可用返回 503；不能当成 Token 失效返回 401，否则前端会误清除有效会话。
        } catch (org.springframework.dao.DataAccessException e) {
            return fail(res, 503, "登录服务暂不可用，请稍后重试");
        } catch (Exception e) {
            return fail(res, 401, "登录已失效，请重新登录");
        }
        int role = ((Number) claims.get("permissions")).intValue();
        req.setAttribute("log.actorId",((Number)claims.get("id")).intValue());
        req.setAttribute("log.actorRole",role);
        boolean userOnly = (path.startsWith("/user/") && !USER_ADMIN.contains(path)) || ENTER_USER.contains(path) || Set.of("/orders/userList", "/orders/userPageList").contains(path) || Set.of("/recharges/userList", "/recharges/userPageList").contains(path) || path.startsWith("/ai/");
        boolean adminOnly = path.startsWith("/admin/") || USER_ADMIN.contains(path) || path.startsWith("/enter/") && !ENTER_USER.contains(path) || path.startsWith("/orders/") && !Set.of("/orders/userList", "/orders/userPageList").contains(path) || path.startsWith("/recharges/") && !Set.of("/recharges/userList", "/recharges/userPageList").contains(path) || path.startsWith("/dashboard/") || path.startsWith("/actuator/") || path.startsWith("/rule/") || path.startsWith("/parking/") && !Set.of("/parking/list", "/parking/catalog").contains(path) || path.startsWith("/spaces/") && !Set.of("/spaces/get", "/spaces/querySpaces", "/spaces/layout", "/spaces/availability").contains(path);
        if (userOnly && role != -1 || adminOnly && role == -1) return fail(res, 403, "权限不足");
        // 平台用户管理、导出、充值流水与监控统一由后端限制为超级管理员，不能只依赖前端隐藏菜单。
        if ((USER_ADMIN.contains(path) || path.equals("/recharges/pageList") || path.equals("/recharges/data") || path.startsWith("/actuator/")) && role != 1)
            return fail(res, 403, "需要超级管理员权限");
        if (path.startsWith("/admin/") && !ADMIN_SELF.contains(path) && role != 1)
            return fail(res, 403, "需要超级管理员权限");
        ThreadLocalUtil.set(claims);
        return true;
    }

    private boolean fail(HttpServletResponse res, int code, String message) throws Exception {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":1,\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception e) {
        ThreadLocalUtil.remove();
    }
}
