package org.zjmproject.interceptors;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.zjmproject.utils.PageQuery;

import java.time.*;
import java.util.*;

@Component
public class QueryValidationInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String page = req.getParameter("pageNum"), size = req.getParameter("pageSize");
        if (page != null || size != null) {
            int p = page == null ? 1 : Integer.parseInt(page), s = size == null ? 20 : Integer.parseInt(size);
            if (p < 1 || p > 1000000 || s < 1 || s > 100)
                throw new IllegalArgumentException("分页参数无效，每页限1至100条");
        }
        String from = req.getParameter("start_time"), to = req.getParameter("end_time");
        PageQuery.range(from == null || from.isBlank() ? null : LocalDateTime.parse(from), to == null || to.isBlank() ? null : LocalDateTime.parse(to));
        for (var entry : req.getParameterMap().entrySet())
            for (String value : entry.getValue())
                if (value.length() > 8000) throw new IllegalArgumentException("请求参数过长");
        String status = req.getParameter("status"), flag = req.getParameter("flag");
        if (status != null && !status.isBlank() && !Set.of("0", "1").contains(status) || flag != null && !flag.isBlank() && !Set.of("0", "1", "2").contains(flag))
            throw new IllegalArgumentException("状态参数无效");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        // PageHelper 使用线程本地变量；异常路径也显式清理，防止容器线程复用时污染下一个请求。
        com.github.pagehelper.PageHelper.clearPage();
    }
}
