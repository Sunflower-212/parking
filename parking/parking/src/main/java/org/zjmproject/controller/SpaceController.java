package org.zjmproject.controller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.*;
import org.zjmproject.service.*;
import java.util.*;

@RestController
@RequestMapping("/spaces")
public class SpaceController {
    @org.springframework.beans.factory.annotation.Autowired private FloorService floors;
    @org.springframework.beans.factory.annotation.Autowired private ReadCache cache;
    private final SpacesService spaces;
    private final JdbcTemplate db;
    private final AccessGuard access;
    private final ParkingFlowService flow;
    public SpaceController(SpacesService spaces,JdbcTemplate db,AccessGuard access,ParkingFlowService flow) {this.spaces=spaces;this.db=db;this.access=access;this.flow=flow;}
    private int lockPark(String name) {
        var ids=db.queryForList("select id from parking where park_name=? for update",Integer.class,name);
        if(ids.isEmpty()) throw new IllegalArgumentException("停车场不存在");
        access.parking(ids.get(0));return ids.get(0);
    }
    private void validate(Spaces s) {
        if(s.getSpaces_name()==null || s.getSpaces_name().isBlank() || s.getSpaces_name().length()>10 || s.getSpaces_col()==null || s.getSpaces_col()<1 || s.getSpaces_col()>100 || s.getSpaces_row()==null || s.getSpaces_row()<1 || s.getSpaces_row()>100 || s.getSpaces_storey()==null || s.getSpaces_storey()<1 || s.getSpaces_flag()==null || !Set.of(0,2,3,4).contains(s.getSpaces_flag()))
            throw new IllegalArgumentException("车位名称、坐标或类型无效；占用状态由出入场服务管理");
    }
    @Transactional @PostMapping("/addSpace") public Result<?> add(@RequestBody Spaces s) {
        validate(s);int park=lockPark(s.getPark_name());
        if(db.queryForObject("select park_storey from parking where id=?",Integer.class,park)<s.getSpaces_storey()) throw new IllegalArgumentException("楼层超出停车场范围");
        spaces.addSpace(s.getPark_name(),s.getSpaces_name(),s.getSpaces_row(),s.getSpaces_col(),s.getSpaces_storey(),s.getSpaces_flag());
        flow.refreshCapacity(park);cache.invalidateAfterCommit("layout:"+park);return Result.success();
    }
    @Transactional @PutMapping("/update") public Result<?> update(@RequestBody Spaces s) {
        validate(s);int park=lockPark(s.getPark_name());
        Spaces current=spaces.findSpacesByInfo(s.getPark_name(),s.getSpaces_row(),s.getSpaces_col(),s.getSpaces_storey());
        if(current==null) throw new IllegalArgumentException("车位不存在");
        if(current.getActive_enter_id()!=null || current.getSpaces_flag()==1) throw new IllegalStateException("预约中或在场车位不能修改");
        spaces.updateSpace(current.getId(),s.getSpaces_name(),s.getSpaces_flag(),null);flow.refreshCapacity(park);cache.invalidateAfterCommit("layout:"+park);return Result.success();
    }
    @Transactional @DeleteMapping("/deleteBatch") public Result<?> delete(@RequestBody List<Spaces> requested) {
        if(requested.isEmpty() || requested.size()>1000) throw new IllegalArgumentException("请选择1至1000个车位");
        SortedSet<Integer> parks=new TreeSet<>();List<Spaces> existing=new ArrayList<>();
        for(Spaces s:requested) {
            Spaces current=spaces.findSpacesByInfo(s.getPark_name(),s.getSpaces_row(),s.getSpaces_col(),s.getSpaces_storey());
            if(current==null) throw new IllegalArgumentException("车位不存在");
            parks.add(current.getPark_id());existing.add(current);
        }
        parks.forEach(id->{db.queryForObject("select id from parking where id=? for update",Integer.class,id);access.parking(id);});
        for(Spaces s:existing) {
            if(db.update("delete from spaces where id=? and spaces_flag<>1 and active_enter_id is null",s.getId())!=1) throw new IllegalStateException("预约中或在场车位不能删除");
        }
        parks.forEach(flow::refreshCapacity);parks.forEach(id->cache.invalidateAfterCommit("layout:"+id));return Result.success();
    }
    @GetMapping("/layout") public Result<?> layout(@RequestParam Integer park_id,@RequestParam Integer spaces_storey){return Result.success(floors.layout(park_id,spaces_storey));}
    @GetMapping("/availability") public Result<?> availability(@RequestParam Integer park_id,@RequestParam Integer spaces_storey){return Result.success(floors.availability(park_id,spaces_storey));}
    @GetMapping("/get") public Result<List<Spaces>> list(@RequestParam Integer park_id,@RequestParam Integer spaces_storey) {return Result.success(legacy(park_id,spaces_storey,null));}
    @GetMapping("/querySpaces") public Result<List<Spaces>> query(@RequestParam Integer park_id,@RequestParam Integer spaces_storey,@RequestParam(required=false) String spaces_name) {return Result.success(legacy(park_id,spaces_storey,spaces_name));}
    private List<Spaces> legacy(int park,int floor,String name) {
        floors.availability(park,floor); // validates ownership before any data is returned
        var rows=name==null?spaces.getSpacesList(park,floor):spaces.selectSpaces(park,floor,name);
        for(var row:rows){row.setUser_name(null);row.setActive_enter_id(null);}return rows;
    }
}
