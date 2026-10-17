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
    @Autowired
    private RechargesService rechargesService;

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
        List<Recharges> rechargesList = rechargesService.findRechargesByUserId(id,amounts,start_time,end_time);
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

    @GetMapping("/data")
    public Result data(){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1 && permissions!=0){
            return Result.error("权限错误");
        }
        LocalDate today = LocalDate.now();
        List<LocalDate> localDateList = new ArrayList<>();
        List<Dict> dictList = new ArrayList<>();
        for(int i=6;i>=0;i--){
            localDateList.add(today.minusDays(i));
        }
        for(LocalDate localDate:localDateList){
            List<Recharges> rechargesList = rechargesService.findRechargesListByDate(localDate);
            BigDecimal num = rechargesList.stream().map(Recharges::getAmounts).reduce(BigDecimal.ZERO, BigDecimal::add);
            Integer number = rechargesList.size();
            Dict dict = Dict.create();
            dict.set("date",localDate).set("value",num).set("number",number);
            dictList.add(dict);
        }
        return Result.success(dictList);
    }
}
