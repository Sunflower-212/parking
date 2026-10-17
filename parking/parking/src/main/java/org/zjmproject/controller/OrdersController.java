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
        List<Orders> ordersList = ordersService.userGetOrdersList(id,park_name,start_time,end_time);
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

    @GetMapping("/exitAllData")
    public Result exitAllData(){
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
            List<Orders> enterList = ordersService.findExitListByDate(localDate);
            if(permissions==0) { java.util.Set<Integer> allowed=parkingService.adminGetParkingList((Integer)map.get("id")).stream().map(Parking::getId).collect(java.util.stream.Collectors.toSet()); enterList=enterList.stream().filter(e->allowed.contains(e.getPark_id())).toList(); }
            Integer number = enterList.size();
            Dict dict = Dict.create();
            dict.set("date",localDate).set("number",number);
            dictList.add(dict);
        }
        return Result.success(dictList);
    }

    @GetMapping("/exitData")
    public Result exitData(Integer park_id){
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
        LocalDate today = LocalDate.now();
        List<LocalDate> localDateList = new ArrayList<>();
        List<Dict> dictList = new ArrayList<>();
        for(int i=6;i>=0;i--){
            localDateList.add(today.minusDays(i));
        }
        for(LocalDate localDate:localDateList){
            List<Orders> enterList = ordersService.findExitListByDateAndParkId(park_id,localDate);
            Integer number = enterList.size();
            Dict dict = Dict.create();
            dict.set("date",localDate).set("number",number);
            dictList.add(dict);
        }
        return Result.success(dictList);
    }

    @GetMapping("/allData")
    public Result allData(){
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
            List<Orders> enterList = ordersService.findExitListByDate(localDate);
            if(permissions==0) { java.util.Set<Integer> allowed=parkingService.adminGetParkingList((Integer)map.get("id")).stream().map(Parking::getId).collect(java.util.stream.Collectors.toSet()); enterList=enterList.stream().filter(e->allowed.contains(e.getPark_id())).toList(); }
            BigDecimal value = enterList.stream().map(Orders::getMoneys).reduce(BigDecimal.ZERO, BigDecimal::add);
            Dict dict = Dict.create();
            dict.set("date",localDate).set("value",value);
            dictList.add(dict);
        }
        return Result.success(dictList);
    }

    @GetMapping("/data")
    public Result data(Integer park_id){
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
        LocalDate today = LocalDate.now();
        List<LocalDate> localDateList = new ArrayList<>();
        List<Dict> dictList = new ArrayList<>();
        for(int i=6;i>=0;i--){
            localDateList.add(today.minusDays(i));
        }
        for(LocalDate localDate:localDateList){
            List<Orders> enterList = ordersService.findExitListByDateAndParkId(park_id,localDate);
            BigDecimal value = enterList.stream().map(Orders::getMoneys).reduce(BigDecimal.ZERO, BigDecimal::add);
            Dict dict = Dict.create();
            dict.set("date",localDate).set("value",value);
            dictList.add(dict);
        }
        return Result.success(dictList);
    }


}
