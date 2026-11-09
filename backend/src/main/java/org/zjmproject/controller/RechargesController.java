package org.zjmproject.controller;

import java.math.BigDecimal;
import cn.hutool.core.lang.Dict;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Orders;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.Recharges;
import org.zjmproject.pojo.Result;
import org.zjmproject.service.RechargesService;
import org.zjmproject.service.AccessGuard;
import org.zjmproject.utils.ThreadLocalUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/recharges")
public class RechargesController {
    @Autowired private org.zjmproject.service.DashboardService dashboard;
    @Autowired
    private RechargesService rechargesService;

    @GetMapping("/userPageList")
    public Result<PageBean<Recharges>> userPageList(@RequestParam(defaultValue="1") Integer pageNum,@RequestParam(defaultValue="20") Integer pageSize,@RequestParam(required=false) BigDecimal amounts,@RequestParam(required=false) LocalDateTime start_time,@RequestParam(required=false) LocalDateTime end_time) {
        int id=AccessGuard.userId();org.zjmproject.utils.PageQuery.range(start_time,end_time);
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        var rows=rechargesService.findRechargesByUserId(id,amounts,start_time,end_time);
        var page=(com.github.pagehelper.Page<Recharges>)rows;
        PageBean<Recharges> result=new PageBean<>();result.setTotal(page.getTotal());result.setItems(page.getResult());return Result.success(result);
    }

    @GetMapping("/userList")
    public Result<List<Recharges>> userRechargesList(
            @RequestParam(required = false) BigDecimal amounts,
            @RequestParam(required = false) LocalDateTime start_time,
            @RequestParam(required = false) LocalDateTime end_time
    ){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=-1){
            return Result.error("管理员无法访问");
        }
        org.zjmproject.utils.PageQuery.range(start_time,end_time);
        com.github.pagehelper.PageHelper.startPage(1,101,false);
        List<Recharges> rechargesList = rechargesService.findRechargesByUserId(id,amounts,start_time,end_time);
        if(rechargesList.size()>100) return Result.error("记录超过100条，请使用分页接口");
        return Result.success(rechargesList);
    }
    @GetMapping("/pageList")
    public Result<PageBean<Recharges>> allUserRechargesPageList(
            Integer pageNum,
            Integer pageSize,
            @RequestParam(required = false) BigDecimal amounts,
            @RequestParam(required = false) LocalDate date){
        Map<String, Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions==-1){
            return Result.error("权限不足,普通用户无法访问");
        }
        PageBean<Recharges> rechargesPageBean = rechargesService.findRechargesByDate(pageNum,pageSize,amounts,date);
        return Result.success(rechargesPageBean);
    }

    @GetMapping("/data") public Result<?> data(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("value",row.get("rechargeAmount"));item.put("number",row.get("rechargeCount"));output.add(item);
        }return Result.success(output);
    }


}
