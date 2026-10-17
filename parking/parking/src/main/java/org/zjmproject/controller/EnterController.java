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

    @Transactional
    @PostMapping("/cancelReservation")
    public Result cancelReservation(Integer id) {
        if(id==null) throw new IllegalArgumentException("缺少预约ID");
        flow.cancel(AccessGuard.userId(),id); return Result.success();
    }


    @Transactional
    @PatchMapping("/reservation")
    public Result reservation(@RequestBody Spaces spaces) {
        if(spaces.getId()==null) throw new IllegalArgumentException("请选择车位");
        return Result.success(flow.reserve(AccessGuard.userId(),spaces.getId()));
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
            List<Enter> enterList = enterService.findEnterListByDate(localDate);
            if(permissions==0) { java.util.Set<Integer> allowed=parkingService.adminGetParkingList((Integer)map.get("id")).stream().map(Parking::getId).collect(java.util.stream.Collectors.toSet()); enterList=enterList.stream().filter(e->allowed.contains(e.getPark_id())).toList(); }
            Integer number = enterList.size();
            Dict dict = Dict.create();
            dict.set("date",localDate).set("number",number);
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
            List<Enter> enterList = enterService.findEnterListByDateAndParkId(park_id,localDate);
            Integer number = enterList.size();
            Dict dict = Dict.create();
            dict.set("date",localDate).set("number",number);
            dictList.add(dict);
        }
        return Result.success(dictList);

    }

}
