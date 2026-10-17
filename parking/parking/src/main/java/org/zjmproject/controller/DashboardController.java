package org.zjmproject.controller;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Result;
import org.zjmproject.service.DashboardService;
import org.zjmproject.utils.ThreadLocalUtil;
import java.util.Map;
@RestController @RequestMapping("/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    public DashboardController(DashboardService dashboard){this.dashboard=dashboard;}
    @GetMapping("/summary") public Result<?> summary(@RequestParam(required=false) Integer park_id,@RequestParam(defaultValue="7") int days) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        return Result.success(dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,days));
    }
}
