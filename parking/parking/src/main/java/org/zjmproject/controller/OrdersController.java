package org.zjmproject.controller;

import java.math.BigDecimal;
import cn.hutool.core.lang.Dict;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.zjmproject.pojo.*;
import org.zjmproject.service.*;
import org.zjmproject.utils.MoneysUtil;
import org.zjmproject.utils.ThreadLocalUtil;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CrossOrigin
@RestController
@RequestMapping("/orders")
public class OrdersController {
    @Autowired private org.zjmproject.service.DashboardService dashboard;
    @Autowired private ParkingFlowService flow;
    @Autowired private AccessGuard access;
    @Autowired private PlateUploadService uploads;
    @Autowired
    private OrdersService ordersService;
    @Autowired
    private ParkingService parkingService;
    @Autowired
    private UserService userService;
    @Autowired
    private EnterService enterService;
    @Autowired
    private SpacesService spacesService;
    @Autowired
    private LicensePlateRecognitionService licensePlateRecognitionService;

    //出库
    @PostMapping("/upload")
    public Result upload(MultipartFile file, Integer park_id) throws Exception {
        if(park_id==null) throw new IllegalArgumentException("请选择停车场");
        access.parking(park_id);
        return Result.success(flow.exit(uploads.recognize(file),park_id));
    }

    @GetMapping("/userPageList")
    public Result<PageBean<Orders>> userPageList(@RequestParam(defaultValue="1") Integer pageNum,@RequestParam(defaultValue="20") Integer pageSize,@RequestParam(required=false) String park_name,@RequestParam(required=false) LocalDateTime start_time,@RequestParam(required=false) LocalDateTime end_time) {
        int id=AccessGuard.userId();org.zjmproject.utils.PageQuery.range(start_time,end_time);
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        var rows=ordersService.userGetOrdersList(id,park_name,start_time,end_time);
        var page=(com.github.pagehelper.Page<Orders>)rows;
        PageBean<Orders> result=new PageBean<>();result.setTotal(page.getTotal());result.setItems(page.getResult());return Result.success(result);
    }

    @GetMapping("/userList")
    public Result<List<Orders>> userGetOrdersList(
            @RequestParam (required = false) String park_name,
            @RequestParam (required = false) LocalDateTime start_time,
            @RequestParam (required = false) LocalDateTime end_time
    ){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=-1){
            return Result.error("管理员无法访问");
        }
        org.zjmproject.utils.PageQuery.range(start_time,end_time);
        com.github.pagehelper.PageHelper.startPage(1,101,false);
        List<Orders> ordersList = ordersService.userGetOrdersList(id,park_name,start_time,end_time);
        if(ordersList.size()>100) return Result.error("记录超过100条，请使用分页接口");
        return Result.success(ordersList);
    }

    @GetMapping("/adminPageList")
    public Result<PageBean<Orders>> adminGetOrdersList(
            @RequestParam Integer pageNum,
            @RequestParam Integer pageSize,
            @RequestParam(required = false) String user_name,
            @RequestParam(required = false) String park_name,
            @RequestParam(required = false) String admin_name,
            @RequestParam(required = false) LocalDate date){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        Integer permissions = (Integer) map.get("permissions");
        PageBean<Orders> pageBean;
        if(permissions == 0){
            pageBean = ordersService.adminGetPageList(pageNum,pageSize,user_name,park_name,date,id);
            return Result.success(pageBean);
        }
        if(permissions == 1){
            pageBean = ordersService.rootGetPageList(pageNum,pageSize,user_name,park_name,date,admin_name);
            return Result.success(pageBean);
        }
        return Result.error("权限错误");
    }

    @GetMapping("/allNumber")
    public Result allNumber(){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1){
            return Result.error("权限错误");
        }
        Integer allExitNumber = ordersService.findTodayExit();
        return Result.success(allExitNumber);
    }

    @GetMapping("/number")
    public Result number(Integer park_id){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1 && permissions!=0){
            return Result.error("权限错误");
        }
        access.parking(park_id);
        Parking parking = parkingService.findParkingById(park_id);
        if(parking==null){
            return Result.error("停车场不存在");
        }
        Integer allExitNumber = ordersService.findTodayExitByParkId(park_id);
        return Result.success(allExitNumber);
    }

    @GetMapping("/exitAllData") public Result<?> exitAllData(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("number",row.get("exited"));output.add(item);
        }return Result.success(output);
    }

    @GetMapping("/exitData") public Result<?> exitData(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("number",row.get("exited"));output.add(item);
        }return Result.success(output);
    }

    @GetMapping("/allData") public Result<?> allData(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("value",row.get("revenue"));output.add(item);
        }return Result.success(output);
    }

    @GetMapping("/data") public Result<?> data(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("value",row.get("revenue"));output.add(item);
        }return Result.success(output);
    }


}
