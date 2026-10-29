package org.zjmproject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.zjmproject.observability.RuntimeEvents;
import org.zjmproject.utils.ThreadLocalUtil;
import java.time.*;
import java.util.*;

@Service
public class AuditService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean background;
    private final TransactionTemplate independent;
    private final Clock clock;
    public AuditService(JdbcTemplate db,ObjectMapper json,MeterRegistry metrics,PlatformTransactionManager manager,
                        Clock clock,@Value("${parking.background.enabled:false}") boolean background) {
        this.db=db;this.json=json;this.metrics=metrics;this.clock=clock;this.background=background;
        independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(2);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid audit data"); }
    }
    /** The intent is durable in the same transaction; broker availability never decides success. */
    public void success(String action,Collection<?> ids) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Audit requires the business transaction");
        Map<String,Object> actor=ThreadLocalUtil.get();
        if (actor==null) throw new IllegalStateException("Missing audit actor");
        record(action,ids,((Number)actor.get("id")).intValue(),((Number)actor.get("permissions")).intValue(),"SUCCESS",null,background);
    }
    public void record(String action,Collection<?> ids,Integer actor,Integer role,String result,String reason,boolean enqueue) {
        if (!action.matches("[a-zA-Z0-9_.-]{1,50}")) throw new IllegalArgumentException("Invalid audit action");
        var payload=new LinkedHashMap<String,Object>();
        payload.put("action",action);payload.put("ids",ids);payload.put("role",role);
        payload.put("result",result);payload.put("reason",reason);payload.put("requestId",RuntimeEvents.requestId());
        String id=UUID.randomUUID().toString();
        if (enqueue) db.update("insert into background_event(id,actor_id,kind,request_key,payload,request_id) values(?,?,'AUDIT',?,?,?)",id,actor,id,encode(payload),RuntimeEvents.requestId());
        else persist(id,actor,LocalDateTime.now(clock),payload);
        RuntimeEvents.committed("audit.recorded",result,id);
    }
    /** Also accepts pre-upgrade payloads; new fields stay null rather than inventing history. */
    public void persist(String eventId,Object actor,Object occurredAt,Map<String,Object> payload) {
        if (!(payload.get("action") instanceof String action) || !action.matches("[a-zA-Z0-9_.-]{1,50}")) throw new IllegalArgumentException("Invalid audit action");
        // 只忽略重复事件；字段损坏必须进入重试，不能被 INSERT IGNORE 静默当作已完成。
        db.update("insert into admin_audit(event_id,actor_id,action,entity_ids,occurred_at,actor_role,result,reason_code,request_id) values(?,?,?,?,?,?,?,?,?) on duplicate key update event_id=event_id",
            eventId,actor,payload.get("action"),encode(payload.getOrDefault("ids",List.of())),occurredAt,
            payload.get("role"),payload.get("result"),payload.get("reason"),payload.get("requestId"));
    }
    public void bestEffort(String action,Collection<?> ids,Integer actor,Integer role,String result,String reason) {
        try { independent.executeWithoutResult(t->record(action,ids,actor,role,result,reason,false)); }
        catch (RuntimeException e) {
            metrics.counter("parking.audit.record.failure").increment();
            LoggerFactory.getLogger(AuditService.class).atWarn().addKeyValue("event","audit.record_failed")
                .addKeyValue("action",action).addKeyValue("reasonCode",e.getClass().getSimpleName()).log("audit_unavailable");
        }
    }
    public void requestFinished(HttpServletRequest request,String route,String result) {
        String action=REQUEST_ACTIONS.get(route);
        if (action==null) return;
        boolean security=route.equals("/admin/login") || route.equals("/admin/logout");
        boolean download=route.equals("/user/export") || route.equals("/admin/background/exports/{id}/file");
        if (!security && !download && "SUCCESS".equals(result)) return;
        Integer actor=(Integer)request.getAttribute("log.actorId"),role=(Integer)request.getAttribute("log.actorRole");
        Collection<?> ids=List.of();
        Object variables=request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(download && variables instanceof Map<?,?> values && values.get("id") instanceof String id && id.matches("[a-f0-9-]{36}")) ids=List.of(id);
        bestEffort(action,ids,actor,role,result,"SUCCESS".equals(result)?null:"REQUEST_REJECTED");
    }
    private static final Map<String,String> REQUEST_ACTIONS=Map.ofEntries(
        Map.entry("/admin/login","admin.login"),Map.entry("/admin/logout","admin.logout"),
        Map.entry("/admin/register","admin.create"),Map.entry("/admin/addAdmin","admin.create"),
        Map.entry("/admin/delete","admin.delete"),Map.entry("/admin/resetPassword","admin.password_reset"),Map.entry("/admin/updatePassword","admin.password_change"),
        Map.entry("/user/addUser","user.create"),Map.entry("/user/delete","user.delete"),Map.entry("/user/resetPassword","user.password_reset"),
        Map.entry("/user/export","export.download"),Map.entry("/admin/background/exports","export.create"),Map.entry("/admin/background/exports/{id}/file","export.download"),
        Map.entry("/rule/create","rule.create"),Map.entry("/rule/update","rule.update"),Map.entry("/rule/delete","rule.delete"),
        Map.entry("/parking/addParking","parking.create"),Map.entry("/parking/rootAddParking","parking.create"),
        Map.entry("/parking/updateParking","parking.update"),Map.entry("/parking/rootUpdateParking","parking.update"),Map.entry("/parking/delete","parking.delete"),
        Map.entry("/spaces/addSpace","layout.add"),Map.entry("/spaces/update","layout.update"),Map.entry("/spaces/deleteBatch","layout.delete"));

    private void root() {
        Map<String,Object> actor=ThreadLocalUtil.get();
        if (actor==null || ((Number)actor.get("permissions")).intValue()!=1) throw new IllegalArgumentException("需要超级管理员权限");
    }
    private static final String FIELDS="event_id eventId,actor_id actorId,actor_role actorRole,action,entity_ids entityIds,result,reason_code reasonCode,request_id requestId,occurred_at occurredAt";
    public Map<String,Object> page(int page,int size,LocalDateTime from,LocalDateTime to,Integer actor,String action,String result,String requestId) {
        root();
        if(page<1 || page>1000000 || size<1 || size>100 || actor!=null && actor<1) throw new IllegalArgumentException("分页或操作人参数无效");
        if(to==null) to=LocalDateTime.now(clock);
        if(from==null) from=to.minusDays(7);
        if(!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0) throw new IllegalArgumentException("查询时间范围需在31天以内");
        StringBuilder where=new StringBuilder(" where occurred_at>=? and occurred_at<?");
        var args=new ArrayList<Object>(List.of(from,to));
        if(actor!=null){where.append(" and actor_id=?");args.add(actor);}
        if(action!=null && !action.isBlank()) {if(!action.matches("[a-zA-Z0-9_.-]{1,50}"))throw new IllegalArgumentException("操作类型无效");where.append(" and action=?");args.add(action);}
        if(result!=null && !result.isBlank()) {if(!Set.of("SUCCESS","FAILURE","ERROR","TIMEOUT").contains(result))throw new IllegalArgumentException("结果无效");where.append(" and result=?");args.add(result);}
        if(requestId!=null && !requestId.isBlank()) {if(!requestId.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("请求ID无效");where.append(" and request_id=?");args.add(requestId);}
        Long total=db.queryForObject("select count(*) from admin_audit"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var items=db.queryForList("select "+FIELDS+" from admin_audit"+where+" order by occurred_at desc,event_id desc limit ? offset ?",args.toArray());
        return Map.of("total",total,"items",items);
    }
    public Map<String,Object> detail(String id) {
        root();if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("记录ID无效");
        var rows=db.queryForList("select "+FIELDS+" from admin_audit where event_id=?",id);
        if(rows.isEmpty())throw new IllegalArgumentException("审计记录不存在");
        return rows.get(0);
    }
}
