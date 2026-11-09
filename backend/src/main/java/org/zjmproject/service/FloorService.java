package org.zjmproject.service;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.zjmproject.utils.ThreadLocalUtil;
import java.time.Duration;
import java.util.*;
@Service
public class FloorService {
    private final JdbcTemplate db;private final ReadCache cache;private final AccessGuard access;
    public FloorService(JdbcTemplate db,ReadCache cache,AccessGuard access){this.db=db;this.cache=cache;this.access=access;}
    private void check(int park,int floor) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        if(((Number)actor.get("permissions")).intValue()!=-1) access.parking(park);
        if(park<1 || floor<1 || floor>100) throw new IllegalArgumentException("停车场或楼层不存在");
        // Only public metadata is cached; ownership above is always checked in MySQL.
        // Sharing the catalog revision invalidates both missing and existing parks on creation/edit.
        var metadata=cache.get("catalog","park-meta:"+park,Duration.ofMinutes(10),new TypeReference<Map<String,Object>>(){},()->{
            var rows=db.queryForList("select id,park_storey from parking where id=?",park);
            return rows.isEmpty()?null:rows.get(0);
        });
        if(metadata==null || ((Number)metadata.get("park_storey")).intValue()<floor) throw new IllegalArgumentException("停车场或楼层不存在");
    }
    // 缓存只包含静态位置和结构类型，占用与空闲在布局中统一；实时状态由 availability 单独查库。
    public List<Map<String,Object>> layout(int park,int floor) {
        check(park,floor);
        return cache.get("layout:"+park,Integer.toString(floor),Duration.ofMinutes(5),new TypeReference<List<Map<String,Object>>>(){},()->db.queryForList("select id,park_id,spaces_name,spaces_col,spaces_row,spaces_storey,case when spaces_flag in (0,1) then 0 else spaces_flag end spaces_flag from spaces where park_id=? and spaces_storey=? order by spaces_row,spaces_col",park,floor));
    }
    // 仅返回车位 ID 与占用标志，不缓存实时状态，也不向普通用户暴露占用者身份。
    public List<Map<String,Object>> availability(int park,int floor) {
        check(park,floor);
        return db.queryForList("select id,spaces_flag from spaces where park_id=? and spaces_storey=? and spaces_flag in (0,1) order by id",park,floor);
    }
    public List<Map<String,Object>> catalog() {
        Map<String,Object> actor=ThreadLocalUtil.get();int role=((Number)actor.get("permissions")).intValue(),id=((Number)actor.get("id")).intValue();
        // 角色与管理员 ID 纳入键，防止管理员各自的数据范围共用同一个缓存结果。
        return cache.get("catalog",role+":"+id,Duration.ofMinutes(10),new TypeReference<List<Map<String,Object>>>(){},()->role==0?db.queryForList("select id,park_name,park_address,park_storey,rule_id from parking where admin_id=? order by id",id):db.queryForList("select id,park_name,park_address,park_storey,rule_id from parking order by id"));
    }
}
