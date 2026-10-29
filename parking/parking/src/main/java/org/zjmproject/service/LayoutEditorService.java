package org.zjmproject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zjmproject.utils.ThreadLocalUtil;
import java.security.MessageDigest;
import java.util.*;

/** A draft changes one floor atomically; live occupancy never participates in its revision. */
@Service
public class LayoutEditorService {
    public record Create(String clientId,String name,Integer row,Integer col,Integer type) {}
    public record Update(Integer id,String name,Integer type) {}
    public record Batch(int parkId,int floor,String baseRevision,List<Create> creates,List<Update> updates,List<Integer> deleteIds) {}
    public record Cell(int id,int park_id,String spaces_name,int spaces_row,int spaces_col,int spaces_storey,int spaces_flag,boolean editable) {}
    public record Snapshot(String revision,List<Cell> items) {}
    public static class Conflict extends RuntimeException {
        public final String reason,revision; public final List<Integer> ids;
        public Conflict(String reason,String message,String revision,List<Integer> ids){super(message);this.reason=reason;this.revision=revision;this.ids=ids;}
    }
    private final JdbcTemplate db; private final ObjectMapper json; private final ParkingCapacity capacity;
    private final ReadCache cache; private final AuditService audit;
    public LayoutEditorService(JdbcTemplate db,ObjectMapper json,ParkingCapacity capacity,ReadCache cache,AuditService audit){
        this.db=db;this.json=json;this.capacity=capacity;this.cache=cache;this.audit=audit;
    }
    private void lockPark(int park,int floor,boolean write) {
        Map<String,Object> actor=ThreadLocalUtil.get();
        if(actor==null || !(actor.get("permissions") instanceof Number role) || role.intValue()<0)throw new IllegalArgumentException("仅管理员可编辑地图");
        if(park<1 || floor<1 || floor>100)throw new IllegalArgumentException("停车场或楼层无效");
        // 直接使用已锁定的资料校验归属，避免旧快照授权；编辑事务不再反向获取用户锁。
        var rows=db.queryForList("select admin_id,park_storey from parking where id=?"+(write?" for update":" for share"),park);
        if(rows.isEmpty() || ((Number)rows.get(0).get("park_storey")).intValue()<floor)throw new IllegalArgumentException("停车场或楼层不存在");
        if(role.intValue()!=1 && !Objects.equals(((Number)rows.get(0).get("admin_id")).intValue(),((Number)actor.get("id")).intValue()))throw new IllegalArgumentException("无权编辑此停车场");
    }
    private List<Cell> cells(int park,int floor,boolean lock) {
        return db.query("select id,park_id,spaces_name,spaces_row,spaces_col,spaces_storey,spaces_flag,(active_enter_id is null and user_name is null and spaces_flag<>1) editable from spaces where park_id=? and spaces_storey=? order by id"+(lock?" for update":""),
            (r,n)->new Cell(r.getInt("id"),r.getInt("park_id"),r.getString("spaces_name"),r.getInt("spaces_row"),r.getInt("spaces_col"),r.getInt("spaces_storey"),r.getInt("spaces_flag"),r.getBoolean("editable")),park,floor);
    }
    private String revision(List<Cell> rows) {
        try {
            var shape=rows.stream().map(c->List.of(c.id(),c.spaces_row(),c.spaces_col(),c.spaces_name(),c.spaces_flag()==1?0:c.spaces_flag())).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(shape)));
        } catch(Exception e){throw new IllegalStateException("无法生成地图版本");}
    }
    @Transactional public Snapshot snapshot(int park,int floor){lockPark(park,floor,false);var rows=cells(park,floor,false);return new Snapshot(revision(rows),rows);}
    private void validate(String name,Integer type) {
        if(name==null || name.isBlank() || name.length()>10 || type==null || !Set.of(0,2,3,4).contains(type))throw new IllegalArgumentException("名称需为1至10个字符，类型不能是占用状态");
    }
    @Transactional public Snapshot save(Batch batch) {
        if(batch==null)throw new IllegalArgumentException("缺少地图修改");
        var creates=batch.creates()==null?List.<Create>of():batch.creates();
        var updates=batch.updates()==null?List.<Update>of():batch.updates();
        var deletes=batch.deleteIds()==null?List.<Integer>of():batch.deleteIds();
        int count=creates.size()+updates.size()+deletes.size();
        if(count<1 || count>1000)throw new IllegalArgumentException("每次请提交1至1000个位置修改");
        lockPark(batch.parkId(),batch.floor(),true);
        var before=cells(batch.parkId(),batch.floor(),true);String revision=revision(before);
        if(!revision.equals(batch.baseRevision()))throw new Conflict("REVISION_CHANGED","地图已被修改，请放弃草稿并加载最新布局",revision,List.of());
        Map<Integer,Cell> byId=new HashMap<>();before.forEach(c->byId.put(c.id(),c));
        Set<Integer> touched=new HashSet<>();
        for(Integer id:deletes)if(id==null || !touched.add(id) || !byId.containsKey(id))throw new IllegalArgumentException("删除位置重复或不属于当前楼层");
        for(Update u:updates){if(u==null || u.id()==null || !touched.add(u.id()) || !byId.containsKey(u.id()))throw new IllegalArgumentException("修改位置重复或不属于当前楼层");validate(u.name(),u.type());}
        var blocked=touched.stream().filter(id->!byId.get(id).editable()).sorted().toList();
        if(!blocked.isEmpty())throw new Conflict("OCCUPIED","所选位置已预约、在场或关联异常，整批未保存",revision,blocked);
        Set<String> coordinates=new HashSet<>(),clients=new HashSet<>();
        // 不允许用删除+重建偷偷替换已有坐标的身份，旧车位的历史关联始终保留原 ID。
        before.forEach(c->coordinates.add(c.spaces_row()+":"+c.spaces_col()));
        for(Create c:creates){
            if(c==null)throw new IllegalArgumentException("新增位置无效");validate(c.name(),c.type());
            if(c.clientId()==null || !c.clientId().matches("[A-Za-z0-9-]{1,64}") || !clients.add(c.clientId()) || c.row()==null || c.col()==null || c.row()<1 || c.row()>100 || c.col()<1 || c.col()>100)throw new IllegalArgumentException("新增位置的标识或坐标无效");
            if(!coordinates.add(c.row()+":"+c.col()))throw new IllegalArgumentException("新增坐标重复，不能覆盖已有位置");
        }
        var changed=updates.stream().filter(u->{var c=byId.get(u.id());return !c.spaces_name().equals(u.name()) || c.spaces_flag()!=u.type();}).toList();
        if(changed.isEmpty() && deletes.isEmpty() && creates.isEmpty())throw new IllegalArgumentException("没有实际修改");
        if(!deletes.isEmpty())db.batchUpdate("delete from spaces where id=?",deletes.stream().sorted().map(id->new Object[]{id}).toList());
        if(!changed.isEmpty())db.batchUpdate("update spaces set spaces_name=?,spaces_flag=?,update_time=now() where id=?",changed.stream().sorted(Comparator.comparing(Update::id)).map(u->new Object[]{u.name(),u.type(),u.id()}).toList());
        if(!creates.isEmpty())db.batchUpdate("insert into spaces(park_id,spaces_name,spaces_row,spaces_col,spaces_storey,spaces_flag,create_time,update_time) values(?,?,?,?,?,?,now(),now())",creates.stream().map(c->new Object[]{batch.parkId(),c.name(),c.row(),c.col(),batch.floor(),c.type()}).toList());
        var after=cells(batch.parkId(),batch.floor(),false);var result=new Snapshot(revision(after),after);
        audit.success("layout.batch",List.of(batch.parkId()));cache.invalidateAfterCommit("layout:"+batch.parkId());
        capacity.rebuild(batch.parkId());return result;
    }
}
