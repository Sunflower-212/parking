package org.zjmproject.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.zjmproject.utils.ThreadLocalUtil;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** SQL is the durable task ledger; broker messages carry only an event ID. */
@Service
public class BackgroundTasks {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BackgroundTasks.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Path directory;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public BackgroundTasks(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,io.micrometer.core.instrument.MeterRegistry metrics,
            @Value("${parking.background.enabled:false}") boolean enabled,
            @Value("${parking.background.export-dir:./exports}") String directory) {
        this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.enabled=enabled;this.metrics=metrics;
        this.directory=Path.of(directory).toAbsolutePath().normalize();
        if(enabled){
            io.micrometer.core.instrument.Gauge.builder("parking.background.pending",this,t->t.measure("select count(*) from background_event where status in ('READY','PUBLISHING','SENT','RUNNING')")).register(metrics);
            io.micrometer.core.instrument.Gauge.builder("parking.background.dead",this,t->t.measure("select count(*) from background_event where status in ('DEAD_PENDING','DEAD')")).register(metrics);
            io.micrometer.core.instrument.Gauge.builder("parking.background.oldest.age",this,t->t.measure("select coalesce(timestampdiff(second,min(created_at),now()),0) from background_event where status in ('READY','PUBLISHING','SENT','RUNNING')")).baseUnit("seconds").register(metrics);
        }
    }
    private double measure(String sql){try{return db.queryForObject(sql,Double.class);}catch(org.springframework.dao.DataAccessException failure){return Double.NaN;}}
    public boolean enabled(){return enabled;}
    private int root() {
        Map<String,Object> actor=ThreadLocalUtil.get();
        if(actor==null || ((Number)actor.get("permissions")).intValue()!=1)throw new IllegalArgumentException("需要超级管理员权限");
        return ((Number)actor.get("id")).intValue();
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("任务参数无效");}}
    private Map<String,Object> decode(String value){try{return json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("任务数据无效");}}
    public String createExport(String key,Map<String,Object> filters) {
        if(!enabled)throw new IllegalStateException("后台导出未启用，请使用直接导出");
        int actor=root();
        if(key==null || !key.matches("[A-Za-z0-9-]{16,64}"))throw new IllegalArgumentException("请提供有效的请求标识");
        var clean=new LinkedHashMap<String,Object>();
        for(String name:List.of("user_name","plate_num","car_color")) {
            Object raw=filters.get(name);
            if(raw!=null){if(!(raw instanceof String) || raw.toString().length()>100)throw new IllegalArgumentException("筛选条件过长");clean.put(name,raw);}
        }
        Object status=filters.get("status");
        if(status!=null){if(!(status instanceof Number) || !Set.of("0","1").contains(status.toString()))throw new IllegalArgumentException("会员状态无效");clean.put("status",status);}
        return tx.execute(t->{
            // Serializes only administrative task creation, not vehicle operations.
            db.queryForObject("select id from admin where id=? for update",Integer.class,actor);
            var old=db.queryForList("select id from background_event where actor_id=? and kind='EXPORT' and request_key=?",String.class,actor,key);
            if(!old.isEmpty())return old.get(0);
            if(db.queryForObject("select count(*) from background_event where actor_id=? and kind='EXPORT' and status not in ('DONE','EXPIRED','DEAD')",Integer.class,actor)>=3)throw new IllegalStateException("请等待现有导出任务完成");
            clean.put("upperId",db.queryForObject("select coalesce(max(id),0) from user",Long.class));
            String id=UUID.randomUUID().toString();
            db.update("insert into background_event(id,actor_id,kind,request_key,payload) values(?,?,'EXPORT',?,?)",id,actor,key,encode(clean));
            return id;
        });
    }
    public void audit(String action,Collection<Integer> ids) {
        if(!enabled)return;
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Audit must be recorded in the business transaction");
        var actor=ThreadLocalUtil.<Map<String,Object>>get();
        if(actor==null)throw new IllegalStateException("Missing audit actor");
        String id=UUID.randomUUID().toString();
        db.update("insert into background_event(id,actor_id,kind,request_key,payload) values(?,?,'AUDIT',?,?)",id,actor.get("id"),id,encode(Map.of("action",action,"ids",ids)));
    }
    private Map<String,Object> owned(String id) {
        int actor=root();
        var rows=db.queryForList("select *,coalesce(expires_at>now(3),0) file_valid from background_event where id=? and actor_id=? and kind='EXPORT'",id,actor);
        if(rows.isEmpty())throw new IllegalArgumentException("导出任务不存在");
        return rows.get(0);
    }
    private boolean fileValid(Map<String,Object> row){Object value=row.get("file_valid");return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()==1;}
    public Map<String,Object> status(String id) {
        var row=owned(id);
        String state=row.get("status").toString();
        if(row.get("expires_at")!=null && !fileValid(row))state="EXPIRED";
        return Map.of("id",id,"status",state,"createdAt",row.get("created_at").toString(),"error",Objects.toString(row.get("error_code"),""));
    }
    public Path download(String id) {
        var row=owned(id);
        if(!"DONE".equals(row.get("status")) || !fileValid(row))throw new IllegalStateException("文件尚未生成或已过期");
        Path path=safeFile(row.get("result_file").toString());
        if(!Files.isRegularFile(path))throw new IllegalStateException("文件不可用，请重新导出");
        return path;
    }
    private Path safeFile(String name) {
        if(!name.matches("[a-f0-9-]+\\.(csv|tmp)"))throw new IllegalStateException("Invalid export name");
        Path p=directory.resolve(name).normalize();if(!p.startsWith(directory))throw new IllegalStateException("Invalid export path");return p;
    }
    public List<Map<String,Object>> dead(){root();return db.queryForList("select id,kind,attempts,error_code,created_at from background_event where status in ('DEAD','DEAD_PENDING') order by created_at desc,id desc limit 100");}
    public void replay(String id) {
        root();db.update("update background_event set status='READY',attempts=0,available_at=now(3),error_code=null,lease_token=null where id=? and status in ('DEAD','DEAD_PENDING')",id);
    }
    public Map<String,Object> claimDelivery() {
        return tx.execute(t->{
            var rows=db.queryForList("select * from background_event where status in ('READY','PUBLISHING','SENT','RUNNING','DEAD_PENDING') and available_at<=now(3) order by available_at,id limit 1 for update skip locked");
            if(rows.isEmpty())return null;
            var row=rows.get(0);String lease=UUID.randomUUID().toString();boolean dead="DEAD_PENDING".equals(row.get("status"));
            db.update("update background_event set status=?,lease_token=?,available_at=date_add(now(3),interval 30 second) where id=?",dead?"DEAD_PENDING":"PUBLISHING",lease,row.get("id"));
            row.put("lease_token",lease);row.put("dead",dead);return row;
        });
    }
    public void delivered(Map<String,Object> row) {
        db.update("update background_event set status=?,available_at=date_add(now(3),interval 5 minute) where id=? and lease_token=? and status=?",Boolean.TRUE.equals(row.get("dead"))?"DEAD":"SENT",row.get("id"),row.get("lease_token"),Boolean.TRUE.equals(row.get("dead"))?"DEAD_PENDING":"PUBLISHING");
    }
    public void deliveryFailed(Map<String,Object> row) {
        db.update("update background_event set status=?,available_at=date_add(now(3),interval 30 second),error_code='BROKER_UNAVAILABLE' where id=? and lease_token=? and status=?",Boolean.TRUE.equals(row.get("dead"))?"DEAD_PENDING":"READY",row.get("id"),row.get("lease_token"),Boolean.TRUE.equals(row.get("dead"))?"DEAD_PENDING":"PUBLISHING");
    }
    public void consume(String id) throws Exception {
        if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid event ID");
        String lease=UUID.randomUUID().toString();
        var row=tx.execute(t->{
            var rows=db.queryForList("select * from background_event where id=? for update",id);
            if(rows.isEmpty())return null;var r=rows.get(0);
            if(!Set.of("PUBLISHING","SENT").contains(r.get("status")))return null;
            db.update("update background_event set status='RUNNING',lease_token=?,available_at=date_add(now(3),interval 5 minute) where id=?",lease,id);
            return r;
        });
        if(row==null)return;
        try {
            var payload=decode(row.get("payload").toString());
            if("AUDIT".equals(row.get("kind"))) {
                tx.executeWithoutResult(t->{
                    // Check fencing token while holding the event row before writing the effect.
                    var token=db.queryForObject("select lease_token from background_event where id=? for update",String.class,id);
                    if(!lease.equals(token))return;
                    db.update("insert ignore into admin_audit(event_id,actor_id,action,entity_ids,occurred_at) values(?,?,?,?,?)",id,row.get("actor_id"),payload.get("action"),encode(payload.get("ids")),row.get("created_at"));
                    db.update("update background_event set status='DONE',completed_at=now(3),error_code=null where id=?",id);
                });
            } else if("EXPORT".equals(row.get("kind"))) export(id,lease,payload);
            else throw new IllegalStateException("Unknown event type");
        } catch(Exception e) {
            // Durable bounded retry before ACK. A DB failure escapes, leaving the broker delivery unacknowledged.
            int attempt=((Number)row.get("attempts")).intValue()+1;
            metrics.counter("parking.background.execution.failure").increment();
            log.warn("Background event {} attempt {} failed ({})",id,attempt,e.getClass().getSimpleName());
            db.update("update background_event set status=?,attempts=attempts+1,available_at=timestampadd(second,?,now(3)),error_code=? where id=? and lease_token=? and status='RUNNING'",attempt>=5?"DEAD_PENDING":"READY",Math.min(60,1<<attempt),e.getClass().getSimpleName(),id,lease);
        }
    }
    private void export(String id,String lease,Map<String,Object> filter) throws Exception {
        Files.createDirectories(directory);Path tmp=safeFile(lease+".tmp"),file=safeFile(lease+".csv");
        boolean kept=false;
        try {
            try(var out=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                out.write("\ufeff用户名,姓名,车牌,车辆颜色,手机号,会员状态\r\n");
                long heartbeat=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                for(long after=0;;) {
                    var args=new ArrayList<Object>();args.add(after);args.add(filter.get("upperId"));
                    var sql=new StringBuilder("select id,user_name,name,plate_num,car_color,tel_num,status from user where id>? and id<=?");
                    for(String key:List.of("user_name","plate_num","car_color"))if(filter.containsKey(key)){sql.append(" and ").append(key).append(" like ?");args.add("%"+filter.get(key)+"%");}
                    if(filter.containsKey("status")){sql.append(" and status=?");args.add(filter.get("status"));}
                    var rows=db.queryForList(sql.append(" order by id limit 500").toString(),args.toArray());
                    for(var user:rows){boolean first=true;for(String key:List.of("user_name","name","plate_num","car_color","tel_num","status")){if(!first)out.write(',');first=false;out.write(csv(Objects.toString(user.get(key),"")));}out.write("\r\n");}
                    if(System.nanoTime()>=heartbeat) {
                        if(db.update("update background_event set available_at=date_add(now(3),interval 5 minute) where id=? and lease_token=? and status='RUNNING'",id,lease)!=1)throw new IllegalStateException("Lease expired");
                        heartbeat=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                    }
                    if(rows.size()<500)break;after=((Number)rows.get(rows.size()-1).get("id")).longValue();
                }
            }
            try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE);
            kept=db.update("update background_event set status='DONE',completed_at=now(3),expires_at=date_add(now(3),interval 24 hour),result_file=?,error_code=null where id=? and lease_token=? and status='RUNNING'",file.getFileName().toString(),id,lease)==1;
        } finally {Files.deleteIfExists(tmp);if(!kept)Files.deleteIfExists(file);}
    }
    static String csv(String value){if(value.stripLeading().matches("(?s)^[=+@-].*"))value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
    public void cleanup() throws Exception {
        for(var row:db.queryForList("select id,result_file from background_event where kind='EXPORT' and expires_at<=now(3) and status='DONE' order by expires_at,id limit 100")) {
            if(row.get("result_file")!=null)Files.deleteIfExists(safeFile(row.get("result_file").toString()));
            db.update("update background_event set status='EXPIRED',result_file=null where id=? and status='DONE'",row.get("id"));
        }
        // Recover files orphaned by process termination; never touch recent/running exports.
        if(Files.isDirectory(directory))try(var paths=Files.list(directory)) {
            long cutoff=System.currentTimeMillis()-java.time.Duration.ofHours(25).toMillis();
            for(Path path:paths.filter(p->p.getFileName().toString().matches("[a-f0-9-]+\\.(csv|tmp)")).toList())
                if(Files.getLastModifiedTime(path).toMillis()<cutoff)Files.deleteIfExists(path);
        }
    }
}
