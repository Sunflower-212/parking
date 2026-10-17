package org.zjmproject.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zjmproject.pojo.Enter;
import org.zjmproject.utils.MoneysUtil;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** All vehicle transitions lock the user, then parking, then space/entry. */
@Service
public class ParkingFlowService {
    public record ReservationCreated(int id) {}
    private final JdbcTemplate db;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration timeout;
    public ParkingFlowService(JdbcTemplate db, ApplicationEventPublisher events, Clock clock,
                              @Value("${parking.reservation-timeout:30m}") Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("预约时限必须为正数");
        this.db=db; this.events=events; this.clock=clock; this.timeout=timeout;
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new IllegalArgumentException("记录不存在，请刷新后重试");
        return rows.get(0);
    }
    private int number(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
    private Map<String,Object> lockUser(int id) { return one("select * from user where id=? for update",id); }
    private void lockPark(int id) {
        var parking=one("select id,admin_id from parking where id=? for update",id);
        Map<String,Object> actor=org.zjmproject.utils.ThreadLocalUtil.get();
        // OCR may run between the controller's initial check and this transaction.
        // Recheck the current owner while holding the parking lock.
        if(actor!=null && ((Number)actor.get("permissions")).intValue()==0 && number(parking,"admin_id")!=((Number)actor.get("id")).intValue())
            throw new IllegalArgumentException("无权操作此停车场");
    }
    public void refreshCapacity(int parkId) {
        db.update("update parking set park_mount=(select count(*) from spaces where park_id=? and spaces_flag in (0,1)), park_spare=(select count(*) from spaces where park_id=? and spaces_flag=0),update_time=now() where id=?",parkId,parkId,parkId);
    }
    private int insertEntry(Map<String,Object> user,int parkId,int spaceId,int flag,int reservation,LocalDateTime time,LocalDateTime expires) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        db.update(conn->{
            PreparedStatement p=conn.prepareStatement("insert into enter(plate_num,user_id,park_id,space_id,enter_time,flag,reservation,reserved_at,expires_at,update_time) values(?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            p.setString(1,(String)user.get("plate_num")); p.setInt(2,number(user,"id")); p.setInt(3,parkId); p.setInt(4,spaceId);
            p.setObject(5,time); p.setInt(6,flag); p.setInt(7,reservation); p.setObject(8,reservation==1?time:null); p.setObject(9,expires); p.setObject(10,time); return p;
        },keys);
        return Objects.requireNonNull(keys.getKey()).intValue();
    }
    private void takeCapacity(int parkId) {
        if(db.update("update parking set park_spare=park_spare-1,update_time=now() where id=? and park_spare>0",parkId)!=1) throw new IllegalStateException("余位数据异常，请核对停车场容量");
    }
    private void occupy(int spaceId,int entryId,String username) {
        if(db.update("update spaces set spaces_flag=1,user_name=?,active_enter_id=?,update_time=now() where id=? and spaces_flag=0 and active_enter_id is null",username,entryId,spaceId)!=1)
            throw new IllegalStateException("车位已被占用，请刷新后重试");
    }
    private void release(Map<String,Object> entry) {
        if(entry.get("space_id")==null) throw new IllegalStateException("旧记录缺少车位关联，请先完成数据迁移核对");
        if(db.update("update spaces set spaces_flag=0,user_name=null,active_enter_id=null,update_time=now() where id=? and active_enter_id=?",entry.get("space_id"),entry.get("id"))!=1)
            throw new IllegalStateException("车位关联异常，未执行释放，请联系管理员核对");
        if(db.update("update parking set park_spare=park_spare+1,update_time=now() where id=? and park_spare<park_mount",entry.get("park_id"))!=1) throw new IllegalStateException("余位数据异常，请核对停车场容量");
    }
    private void completeEvent(int id) { db.update("update reservation_event set completed_at=now(),last_error=null where reservation_id=?",id); }

    @Transactional
    public Enter reserve(int userId,int spaceId) {
        Map<String,Object> user=lockUser(userId);
        String plate=(String)user.get("plate_num");
        if(plate==null || !plate.matches(org.zjmproject.pojo.User.LICENSE_PLATE_REGEX)) throw new IllegalArgumentException("请先完善有效车牌信息");
        if(db.queryForObject("select count(*) from enter where active_user_id=?",Integer.class,userId)>0)
            throw new IllegalStateException("您已有预约或车辆在场");
        int parkId=number(one("select park_id from spaces where id=?",spaceId),"park_id");
        lockPark(parkId);
        Map<String,Object> space=one("select * from spaces where id=? for update",spaceId);
        if(number(space,"spaces_flag")!=0) throw new IllegalStateException("该车位不可预约");
        LocalDateTime start=now(), expires=start.plus(timeout);
        int id=insertEntry(user,parkId,spaceId,0,1,start,expires);
        occupy(spaceId,id,(String)user.get("user_name")); takeCapacity(parkId);
        db.update("insert into reservation_event(reservation_id,expires_at) values(?,?)",id,expires);
        events.publishEvent(new ReservationCreated(id));
        Enter result=new Enter(); result.setId(id); result.setSpace_id(spaceId); result.setPark_id(parkId); result.setReserved_at(start); result.setExpires_at(expires); result.setFlag(0); result.setReservation(1);
        return result;
    }
    @Transactional
    public void cancel(int userId,int id) {
        lockUser(userId);
        Map<String,Object> initial=one("select * from enter where id=?",id);
        if(number(initial,"user_id")!=userId) throw new IllegalArgumentException("不能取消他人的预约");
        lockPark(number(initial,"park_id"));
        Map<String,Object> entry=one("select * from enter where id=? for update",id);
        if(number(entry,"flag")!=0) throw new IllegalStateException("已入场记录不能取消预约");
        if(number(entry,"reservation")!=1) return;
        db.update("update enter set reservation=2,update_time=now() where id=? and flag=0 and reservation=1",id);
        release(entry); completeEvent(id);
    }
    @Transactional
    public void expire(int id) {
        List<Map<String,Object>> initial=db.queryForList("select * from enter where id=?",id);
        if(initial.isEmpty()) { completeEvent(id); return; }
        lockUser(number(initial.get(0),"user_id")); lockPark(number(initial.get(0),"park_id"));
        Map<String,Object> entry=one("select * from enter where id=? for update",id);
        if(number(entry,"flag")!=0 || number(entry,"reservation")!=1) { completeEvent(id); return; }
        LocalDateTime expiry=db.queryForObject("select expires_at from enter where id=?",LocalDateTime.class,id);
        if(expiry==null) throw new IllegalStateException("预约缺少到期时间，需人工核对");
        if(now().isBefore(expiry)) throw new IllegalStateException("预约尚未到期");
        db.update("update enter set reservation=2,update_time=now() where id=? and flag=0 and reservation=1 and expires_at<=?",id,now());
        release(entry); completeEvent(id);
    }
    @Transactional
    public String enter(String plate,int parkId) {
        Map<String,Object> user=one("select * from user where plate_num=? for update",plate);
        int uid=number(user,"id"); lockPark(parkId);
        if(db.queryForObject("select count(*) from enter where active_user_id=? and flag=1",Integer.class,uid)>0) throw new IllegalStateException("车辆已在场");
        List<Map<String,Object>> reservations=db.queryForList("select * from enter where active_user_id=? and flag=0 and reservation=1 for update",uid);
        if(!reservations.isEmpty()) {
            Map<String,Object> entry=reservations.get(0);
            if(number(entry,"park_id")!=parkId) throw new IllegalStateException("预约属于其他停车场，请先取消原预约");
            LocalDateTime expiry=db.queryForObject("select expires_at from enter where id=?",LocalDateTime.class,entry.get("id"));
            if(expiry==null) throw new IllegalStateException("预约需要完成迁移核对");
            if(!now().isBefore(expiry)) {
                db.update("update enter set reservation=2,update_time=now() where id=?",entry.get("id")); release(entry); completeEvent(number(entry,"id"));
            } else {
                if(entry.get("space_id")==null || db.queryForObject("select count(*) from spaces where id=? and active_enter_id=?",Integer.class,entry.get("space_id"),entry.get("id"))!=1)
                    throw new IllegalStateException("预约车位关联异常");
                db.update("update enter set flag=1,enter_time=?,update_time=now() where id=?",now(),entry.get("id")); completeEvent(number(entry,"id")); return plate;
            }
        }
        List<Integer> free=db.queryForList("select id from spaces where park_id=? and spaces_flag=0 and active_enter_id is null order by id limit 1 for update",Integer.class,parkId);
        if(free.isEmpty()) throw new IllegalStateException("停车场没有可用车位");
        int id=insertEntry(user,parkId,free.get(0),1,0,now(),null);
        occupy(free.get(0),id,(String)user.get("user_name")); takeCapacity(parkId); return plate;
    }
    @Transactional
    public String exit(String plate,int parkId) {
        Map<String,Object> user=one("select * from user where plate_num=? for update",plate);
        int uid=number(user,"id"); lockPark(parkId);
        Map<String,Object> entry=one("select * from enter where active_user_id=? and flag=1 for update",uid);
        if(number(entry,"park_id")!=parkId) throw new IllegalStateException("车辆不在此停车场");
        Map<String,Object> rule=one("select r.* from parking p join rule r on r.id=p.rule_id where p.id=?",parkId);
        LocalDateTime start=db.queryForObject("select enter_time from enter where id=?",LocalDateTime.class,entry.get("id")), end=now();
        int minutes=MoneysUtil.getDuration(start,end);
        BigDecimal fee=MoneysUtil.getMoneys(minutes,number(rule,"free_time"),number(rule,"charging_unit"),new BigDecimal(rule.get("unit_cost").toString()));
        var vip=db.queryForObject("select vip_start_time,vip_end_time from user where id=?",(r,n)->new LocalDateTime[]{r.getObject(1,LocalDateTime.class),r.getObject(2,LocalDateTime.class)},uid);
        LocalDateTime vipStart=vip[0],vipEnd=vip[1];
        if(vipStart!=null && vipEnd!=null && !end.isBefore(vipStart) && end.isBefore(vipEnd)) fee=BigDecimal.ZERO;
        if(db.update("update user set balance=balance-?,update_time=now() where id=? and balance>=?",fee,uid,fee)!=1) throw new IllegalStateException("余额不足");
        db.update("update enter set flag=2,update_time=now() where id=? and flag=1",entry.get("id"));
        release(entry);
        db.update("insert into orders(plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time) values(?,?,?,?,?,?,?,?)",plate,uid,parkId,start,end,minutes,fee,end);
        return plate;
    }
}
