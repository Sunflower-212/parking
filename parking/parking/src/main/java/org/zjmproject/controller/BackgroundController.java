package org.zjmproject.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Result;
import org.zjmproject.service.BackgroundTasks;
import java.util.Map;

@RestController
@RequestMapping("/admin/background")
public class BackgroundController {
    private final BackgroundTasks tasks;
    public BackgroundController(BackgroundTasks tasks){this.tasks=tasks;}
    @GetMapping("/capabilities") public Result<?> capabilities(){return Result.success(Map.of("exports",tasks.enabled()));}
    @PostMapping("/exports") public Result<?> create(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> filter){return Result.success(Map.of("id",tasks.createExport(key,filter)));}
    @GetMapping("/exports/{id}") public Result<?> status(@PathVariable String id){return Result.success(tasks.status(id));}
    // 下载由服务层再次校验任务所有者与有效期；响应 no-store，避免含用户资料的文件被共享缓存保存。
    @GetMapping("/exports/{id}/file") public ResponseEntity<FileSystemResource> file(@PathVariable String id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=users.csv").header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(new FileSystemResource(tasks.download(id)));
    }
    @GetMapping("/dead") public Result<?> dead(){return Result.success(tasks.dead());}
    @PostMapping("/dead/{id}/replay") public Result<?> replay(@PathVariable String id){tasks.replay(id);return Result.success();}
}
