package org.zjmproject.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.zjmproject.ai.*;
import org.zjmproject.pojo.Result;
import java.util.Map;
@RestController
public class AiStatusController {
    private final AiTools tools;private final boolean enabled;private final String key;
    public AiStatusController(AiTools tools,@Value("${parking.integrations.ai:false}") boolean enabled,@Value("${spring.ai.openai.api-key:}") String key){this.tools=tools;this.enabled=enabled;this.key=key;}
    @GetMapping("/ai/status") public Result<?> status(){return Result.success(Map.of("enabled",enabled,"configured",!key.isBlank(),"connectionVerified",false,"capabilities",tools.capabilities(AiActor.current())));}
}
