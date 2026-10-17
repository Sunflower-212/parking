package org.zjmproject.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.*;
import org.zjmproject.service.EnterService;
import org.zjmproject.service.OrdersService;
import org.zjmproject.service.ParkingService;
import org.zjmproject.service.SpacesService;
import org.zjmproject.utils.ThreadLocalUtil;

import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/parking")
public class ParkingController {
    @Autowired private org.zjmproject.service.AccessGuard access;
    @Autowired private org.zjmproject.service.FloorService floors;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired
    private ParkingService parkingService;
    @Autowired
    private EnterService enterService;
    @Autowired
    private OrdersService ordersService;
    @Autowired
    private SpacesService spacesService;

    //普通管理创建停车场，不带admin_id
    @Transactional
    @PostMapping("/addParking")
    public Result addParking(@RequestBody Parking parking){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions == 0){
            if(parking.getId()!=null) access.parking(parking.getId());
            parking.setAdmin_id((Integer) map.get("id"));
            parkingService.addParking(parking);
            return Result.success();
        }
        return Result.error("没有权限创建");
    }

    //超级管理员，创建停车场，带有admin_id
    @Transactional
    @PostMapping("/rootAddParking")
    public Result rootAddParking(@RequestBody Parking parking){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions == 1){
            parkingService.addParking(parking);
            return Result.success();
        }
        return Result.error("没有权限创建");
    }

    //普通管理员,修改停车场信息，不带有admin_id
    @Transactional
    @PutMapping("/updateParking")
    public Result updateParking(@RequestBody Parking parking){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        Parking findParking = parkingService.findParkingById(parking.getId());
        if(findParking==null){
            return Result.error("系统错误，停车场不存在");
        }
        if(permissions == 0){
            if(parking.getId()!=null) access.parking(parking.getId());
            parking.setAdmin_id((Integer) map.get("id"));
            parkingService.updateParking(parking);
            return Result.success();
        }
        return Result.error("权限错误");
    }

    //超级管理员，修改停车场信息，带有admin_id
    @Transactional
    @PutMapping("/rootUpdateParking")
    public Result rootUpdateParking(@RequestBody Parking parking){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        Parking findParking = parkingService.findParkingById(parking.getId());
        if(findParking==null){
            return Result.error("系统错误，停车场不存在");
        }
        if(permissions == 1){
            parkingService.updateParking(parking);
            return Result.success();
        }
        return Result.error("没有权限创建");
    }

    /*
    *管理员查询停车场
     */
    @GetMapping("/selectParking")
    public Result<PageBean<Parking>> selectParking(
            Integer pageNum,
            Integer pageSize,
            @RequestParam (required = false) String park_name,
            @RequestParam (required = false) String park_address,
            @RequestParam (required = false) String rule_name
    ){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions == 0){
            Integer admin_id = (Integer) map.get("id");
            PageBean<Parking> pageBean = parkingService.findParkingByAdminId(pageNum,pageSize,park_name,park_address,rule_name,admin_id);
            return Result.success(pageBean);
        }
        return Result.error("权限错误");
    }
    /*
    *超级管理员
    *  */
    @GetMapping("/selectAllParking")
    public Result<PageBean<Parking>> selectAllParking(
            Integer pageNum,
            Integer pageSize,
            @RequestParam (required = false) String park_name,
            @RequestParam (required = false) String park_address,
            @RequestParam (required = false) String admin_name,
            @RequestParam (required = false) String rule_name
            ){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions == 1){
            PageBean<Parking> pageBean = parkingService.findAllParking(pageNum,pageSize,park_name,park_address,admin_name,rule_name);
            return Result.success(pageBean);
        }
        return Result.error("权限错误");
    }




    @GetMapping("/catalog") public Result<?> catalog(){return Result.success(floors.catalog());}

    @GetMapping("/list")
    public Result<List<Parking>> parkingList(){
        Map<String,Object> actor=ThreadLocalUtil.get();
        List<Parking> parkingList = ((Number)actor.get("permissions")).intValue()==0?parkingService.adminGetParkingList(((Number)actor.get("id")).intValue()):parkingService.parkingList();
        return Result.success(parkingList);
    }

    @GetMapping("/adminGetParkingList")
    public Result<List<Parking>> adminGetParkingList(){
        Map<String,Object>map=ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=0){
            return Result.error("权限错误");
        }
        Integer adminId=(Integer)map.get("id");
        List<Parking> parkingList = parkingService.adminGetParkingList(adminId);
        return Result.success(parkingList);
    }

    @Transactional
    @DeleteMapping("/delete")
    //删除停车场
    public Result delete(String park_name){
        Map <String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        Parking findParking = parkingService.findParkByParkName(park_name);

        if(findParking==null){
            return Result.error("系统错误，停车场不存在");
        }
        Integer parkingId = findParking.getId();
        access.parking(parkingId);
        db.queryForObject("select id from parking where id=? for update",Integer.class,parkingId);
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from enter where park_id=?) or exists(select 1 from orders where park_id=?) or exists(select 1 from spaces where park_id=?)",Boolean.class,parkingId,parkingId,parkingId))) return Result.error("存在该停车场关联数据，无法删除");
        switch (permissions){
            case 0:{
                String adminName = (String) map.get("adminname");
                String thisAdminName = findParking.getAdmin_name();
                if(adminName.equals(thisAdminName)){
                    parkingService.deleteParkingByParkingName(park_name);
                    return Result.success();
                }
                break;
            }
            case 1:{
                parkingService.deleteParkingByParkingName(park_name);
                return Result.success();
            } default:
                break;
        }
        return Result.error("权限不足");

    }

}
