package org.zjmproject.controller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Result;
@RestController
@ConditionalOnProperty(name="parking.integrations.ai",havingValue="false",matchIfMissing=true)
public class DisabledAiController {
    @RequestMapping("/ai/**") public Result<?> unavailable() {return Result.error("AI助手尚未启用，请配置DeepSeek服务");}
}
