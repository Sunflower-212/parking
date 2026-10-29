package org.zjmproject.service;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class DashboardService {
    private final JdbcTemplate db;private final ReadCache cache;private final AccessGuard access;private final Clock clock;
    public DashboardService(JdbcTemplate db,ReadCache cache,AccessGuard access,Clock clock) {this.db=db;this.cache=cache;this.access=access;this.clock=clock;}
    public Map<String,Object> summary(int actor,int role,Integer parkId,int days) {
        if(role!=0 && role!=1) throw new IllegalArgumentException("仅管理员可查看统计");
        if(days<1 || days>31) throw new IllegalArgumentException("统计天数需在1至31之间");
        if(parkId!=null) access.parking(parkId);
        // 统计键包含身份、停车场、天数和业务日期，避免跨权限复用与跨日沿用昨天的结果。
        String scope=role+":"+actor+":"+(parkId==null?"all":parkId)+":"+days+":"+LocalDate.now(clock);
        return cache.get("dashboard",scope,Duration.ofSeconds(30),new TypeReference<Map<String,Object>>(){},()->load(actor,role,parkId,days));
    }
    private Map<String,Object> load(int actor,int role,Integer parkId,int days) {
        LocalDate today=LocalDate.now(clock), first=today.minusDays(days-1);
        List<Object> scopeArgs=new ArrayList<>();
        String scope="";
        // 普通管理员的归属条件直接进入聚合 SQL，不先取全量记录再在 Java 中过滤。
        if(role==0) {scope+=" and p.admin_id=?";scopeArgs.add(actor);}
        if(parkId!=null) {scope+=" and p.id=?";scopeArgs.add(parkId);}
        Map<String,Object> counts=new LinkedHashMap<>(db.queryForMap("select count(*) parkingNumber,coalesce(sum(c.park_mount),0) totalSpaces,coalesce(sum(c.park_spare),0) availableSpaces from parking p join parking_capacity_total c on c.park_id=p.id where 1=1"+scope,scopeArgs.toArray()));
        // 时间范围使用业务时区的左闭右开边界，避免相邻日期统计重复或漏掉末尾精度。
        List<Object> args=new ArrayList<>(List.of(first.atStartOfDay(),today.plusDays(1).atStartOfDay()));args.addAll(scopeArgs);
        // 入场统计排除尚未入场的预约；订单收入按订单创建时间聚合，保持既有统计口径。
        var entries=db.queryForList("select date(e.enter_time) day,count(*) entered from enter e join parking p on p.id=e.park_id where e.enter_time>=? and e.enter_time<? and e.flag<>0"+scope+" group by date(e.enter_time)",args.toArray());
        var orders=db.queryForList("select date(o.create_time) day,count(*) exited,coalesce(sum(o.moneys),0) revenue from orders o join parking p on p.id=o.park_id where o.create_time>=? and o.create_time<?"+scope+" group by date(o.create_time)",args.toArray());
        Map<String,Map<String,Object>> series=new LinkedHashMap<>();
        // 先补齐每天的零值，再合并数据库聚合结果，保证空数据日期也能连续绘图。
        for(int i=0;i<days;i++) {String date=first.plusDays(i).toString();Map<String,Object> row=new LinkedHashMap<>();row.put("date",date);row.put("entered",0);row.put("exited",0);row.put("revenue",0);if(role==1){row.put("rechargeAmount",0);row.put("rechargeCount",0);}series.put(date,row);}
        for(var row:entries) series.get(row.get("day").toString()).put("entered",row.get("entered"));
        for(var row:orders) {var target=series.get(row.get("day").toString());target.put("exited",row.get("exited"));target.put("revenue",row.get("revenue"));}
        // 平台用户、管理员及成功充值指标只为超级管理员查询，响应中也不向普通管理员提供这些字段。
        if(role==1) {
            counts.putAll(db.queryForMap("select count(*) userNumber,coalesce(sum(create_time>=? and create_time<?),0) todayNewUserNumber from user",today.atStartOfDay(),today.plusDays(1).atStartOfDay()));
            counts.put("adminNumber",db.queryForObject("select count(*) from admin",Long.class));
            for(var row:db.queryForList("select date(create_time) day,count(*) rechargeCount,sum(amounts) rechargeAmount from recharges where flag=1 and create_time>=? and create_time<? group by date(create_time)",first.atStartOfDay(),today.plusDays(1).atStartOfDay())) {
                var target=series.get(row.get("day").toString());target.put("rechargeAmount",row.get("rechargeAmount"));target.put("rechargeCount",row.get("rechargeCount"));
            }
        }
        var current=series.get(today.toString());counts.put("enteredToday",current.get("entered"));counts.put("exitedToday",current.get("exited"));counts.put("revenueToday",current.get("revenue"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("counters",counts);result.put("series",new ArrayList<>(series.values()));result.put("updatedAt",LocalDateTime.now(clock).toString());result.put("timezone",clock.getZone().toString());result.put("parkId",parkId);result.put("days",days);return result;
    }
}
