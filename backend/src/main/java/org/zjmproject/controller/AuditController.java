package org.zjmproject.controller;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Result;
import org.zjmproject.service.AuditService;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/audit")
public class AuditController {
    private final AuditService audits;
    public AuditController(AuditService audits) { this.audits=audits; }
    @GetMapping("/page") public Result<?> page(
            @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) LocalDateTime from,@RequestParam(required=false) LocalDateTime to,
            @RequestParam(required=false) Integer actorId,@RequestParam(required=false) String action,
            @RequestParam(required=false) String result,@RequestParam(required=false) String requestId) {
        return Result.success(audits.page(pageNum,pageSize,from,to,actorId,action,result,requestId));
    }
    @GetMapping("/{eventId}") public Result<?> detail(@PathVariable String eventId) { return Result.success(audits.detail(eventId)); }
}
