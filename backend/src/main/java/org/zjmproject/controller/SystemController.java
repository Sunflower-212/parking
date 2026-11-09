package org.zjmproject.controller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Result;
import java.util.Map;
@RestController @RequestMapping("/system")
public class SystemController {
    @Value("${parking.integrations.ai:false}") private boolean ai;
    @Value("${parking.integrations.ocr:false}") private boolean ocr;
    @Value("${parking.integrations.alipay:false}") private boolean alipay;
    @Value("${parking.integrations.oss:false}") private boolean oss;
    @GetMapping("/capabilities") public Result<?> capabilities(){return Result.success(Map.of("ai",ai,"ocr",ocr,"alipay",alipay,"oss",oss));}
}
