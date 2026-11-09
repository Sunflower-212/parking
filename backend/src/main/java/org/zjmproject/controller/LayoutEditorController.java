package org.zjmproject.controller;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.service.LayoutEditorService;
import org.zjmproject.pojo.Result;
@RestController
@RequestMapping("/spaces")
public class LayoutEditorController {
    private final LayoutEditorService editor;
    public LayoutEditorController(LayoutEditorService editor){this.editor=editor;}
    @GetMapping("/editor") public Result<?> snapshot(@RequestParam int park_id,@RequestParam int spaces_storey){return Result.success(editor.snapshot(park_id,spaces_storey));}
    @PostMapping("/layout/batch") public Result<?> save(@RequestBody LayoutEditorService.Batch batch){return Result.success(editor.save(batch));}
}
