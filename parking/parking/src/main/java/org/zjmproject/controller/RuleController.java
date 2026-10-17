package org.zjmproject.controller;

import org.apache.ibatis.annotations.Delete;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Parking;
import org.zjmproject.pojo.Result;
import org.zjmproject.pojo.Rule;
import org.zjmproject.service.ParkingService;
import org.zjmproject.service.RuleService;
import org.zjmproject.utils.ThreadLocalUtil;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@CrossOrigin
@RestController
@RequestMapping("/rule")
@Validated
public class RuleController {
    @Autowired private org.zjmproject.mapper.RuleMapper mapper;
    @GetMapping("/pageList") public Result<?> pageList(@RequestParam(defaultValue="1") Integer pageNum,@RequestParam(defaultValue="20") Integer pageSize,@RequestParam(required=false) String rule_name){
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        com.github.pagehelper.PageHelper.orderBy("id desc");
        var rows=mapper.rules(rule_name);var page=(com.github.pagehelper.Page<Rule>)rows;org.zjmproject.pojo.PageBean<Rule> result=new org.zjmproject.pojo.PageBean<>();result.setTotal(page.getTotal());result.setItems(page.getResult());return Result.success(result);
    }
    @Autowired
    private RuleService ruleService;
    @Autowired
    private ParkingService parkingService;

    @Transactional
    @PostMapping("/create")
    public Result createRule(@RequestBody @Validated Rule rule){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=0&&permissions!=1){
            return Result.error("非管理员不能创建计费规则");
        }
        ruleService.createRule(rule);
        return Result.success();
    }


    @GetMapping("/select")
    public Result<List<Rule>> selectRule(@RequestParam(required = false) String rule_name){
        List<Rule> ruleList = ruleService.selectRule(rule_name);
        return  Result.success(ruleList);
    }

    @Transactional
    @DeleteMapping("/delete")
    public Result deleteRule(Integer id){
        if(((Number)ThreadLocalUtil.<Map<String,Object>>get().get("permissions")).intValue()!=1) return Result.error("共享计费规则仅超级管理员可删除");
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=0&&permissions!=1){
            return Result.error("非管理员不能删除计费规则");
        }
        List<Parking> parkingList = parkingService.findParkByRuleId(id);
        if(!parkingList.isEmpty()){
            return Result.error("存在停车场使用此规则无法删除");
        }
        if(ruleService.findRuleById(id)==null){
            return Result.error("该计费规则不存在");
        }

        ruleService.deleteRule(id);
        return Result.success();
    }

    @Transactional
    @PutMapping("/update")
    public Result updateRule(@RequestBody @Validated Rule rule){
        if(((Number)ThreadLocalUtil.<Map<String,Object>>get().get("permissions")).intValue()!=1) return Result.error("共享计费规则仅超级管理员可修改");
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=0&&permissions!=1){
            return Result.error("非管理员不能修改计费规则");
        }
        Rule findRule1 = ruleService.findRuleById(rule.getId());
        if(findRule1==null){
            return Result.error("此规则不存在");
        }
        Rule findRule2 = ruleService.findRuleByRuleName(rule.getRule_name());
        if(findRule2!=null && !Objects.equals(findRule2.getId(), rule.getId())){
            return  Result.error("该规则名称已被占用");
        }
        ruleService.updateRule(rule);
        return Result.success();
    }
}
