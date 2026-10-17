package org.zjmproject.controller;

import cn.hutool.core.lang.Dict;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.zjmproject.pojo.*;
import org.zjmproject.service.*;
import org.zjmproject.utils.ThreadLocalUtil;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

@CrossOrigin
@RestController
@RequestMapping("/enter")
public class EnterController {
    public record ReservationRequest(Integer id) {}
    @Autowired private org.zjmproject.service.DashboardService dashboard;
    @Autowired private ParkingFlowService flow;
    @Autowired private AccessGuard access;
    @Autowired private PlateUploadService uploads;
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

    @PostMapping("/upload")
    public Result upload(MultipartFile file, Integer park_id) throws Exception {
        if(park_id==null) throw new IllegalArgumentException("请选择停车场");
        access.parking(park_id);
        return Result.success(flow.enter(uploads.recognize(file),park_id));
    }


    //管理员查询所有入场信息,查询在库信息，只需要flag=1
    @GetMapping("/adminPageList")
    public Result<PageBean<Enter>> allEnter(
            Integer pageNum,
            Integer pageSize,
            @RequestParam (required = false) String plate_num,
            @RequestParam (required = false) String park_name,
            @RequestParam (required = false) Integer flag,
            @RequestParam (required = false) String admin_name){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        Integer id = (Integer)map.get("id");
        PageBean<Enter> pageBean;
        if(permissions==0){
            pageBean = enterService.adminGetPageList(pageNum,pageSize,id,plate_num,park_name,flag);
            return Result.success(pageBean);
        }else if(permissions==1){
            pageBean = enterService.rootGetPageList(pageNum,pageSize,plate_num,park_name,flag,admin_name);
            return Result.success(pageBean);
        }
        return Result.error("权限错误");
    }

    //用户查询所有入场信息
    @GetMapping("/userPageList")
    public Result<PageBean<Enter>> enterPageList(
            @RequestParam Integer pageNum,
            @RequestParam Integer pageSize,
            @RequestParam (required = false) String park_name,
            @RequestParam (required = false) LocalDate date){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        Integer id = (Integer)map.get("id");
        PageBean<Enter> pageBean;
        //用户，获取自己的入场信息
        if(permissions==-1){
            pageBean = enterService.userGetPageList(pageNum,pageSize,park_name,id,date);
            return Result.success(pageBean);
        }
        return Result.error("权限错误");
    }

    @GetMapping("/userEnterOrReservation")
    public Result<List<Enter>> userEnterOrReservation(){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        Integer userId = (Integer)map.get("id");

        //用户，获取自己的在库或者预约信息
        if(permissions==-1){
            Integer flag = 1;
            List<Enter> isEnterList = enterService.isVehicle(userId,flag);
            if(!isEnterList.isEmpty())
                return Result.success(isEnterList);

            flag=0;
            Integer reservation = 1;
            List<Enter> isReservationList = enterService.isReservation(userId,flag,reservation);
            if(!isReservationList.isEmpty())
                return Result.success(isReservationList);
            //未预约或者不在库
            return Result.success(null);
        }
        return Result.error("权限错误");
    }

    @PostMapping("/cancelReservation")
    public Result cancelReservation(Integer id) {
        if(id==null) throw new IllegalArgumentException("缺少预约ID");
        flow.cancel(AccessGuard.userId(),id); return Result.success();
    }


    @PatchMapping("/reservation")
    public Result reservation(@RequestBody ReservationRequest spaces) {
        if(spaces.id()==null) throw new IllegalArgumentException("请选择车位");
        return Result.success(flow.reserve(AccessGuard.userId(),spaces.id()));
    }

    @GetMapping("/allNumber")
    public Result allNumber(){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1){
            return Result.error("权限错误");
        }
        Integer allEnterNumber = enterService.findTodayEnter();
        return Result.success(allEnterNumber);
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
        Integer allEnterNumber = enterService.findTodayEnterByParkId(park_id);
        return Result.success(allEnterNumber);
    }

    @GetMapping("/allData") public Result<?> allData(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("number",row.get("entered"));output.add(item);
        }return Result.success(output);
    }

    @GetMapping("/data") public Result<?> data(@RequestParam(required=false) Integer park_id) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        var result=dashboard.summary(((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),park_id,7);
        var output=new java.util.ArrayList<Map<String,Object>>();
        for(var row:(java.util.List<Map<String,Object>>)result.get("series")) {
            var item=new java.util.LinkedHashMap<String,Object>();item.put("date",row.get("date"));item.put("number",row.get("entered"));output.add(item);
        }return Result.success(output);
    }


}
