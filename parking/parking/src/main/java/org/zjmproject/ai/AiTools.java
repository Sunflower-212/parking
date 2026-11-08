package org.zjmproject.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.zjmproject.service.DashboardService;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** One validation boundary for every model-provided argument. No tool writes business data. */
@Service
public class AiTools {
    private final JdbcTemplate db;
    private final DashboardService dashboard;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public AiTools(JdbcTemplate db, DashboardService dashboard, Clock clock) { this.db=db;this.dashboard=dashboard;this.clock=clock; }
    private record Field(String type, boolean required, int max) {}
    private record Spec(String name,String description,Map<String,Field> fields) {}
    private static Field integer(boolean required,int max) {return new Field("integer",required,max);}
    private static Field string(boolean required,int max) {return new Field("string",required,max);}
    private List<Spec> specs(AiActor a) {
        var list=new ArrayList<>(List.of(
            new Spec("findParking","按名称或地址查询可访问的停车场，每次最多20个；有多个结果时请用户选择",Map.of("keyword",string(false,100),"page",integer(false,10000))),
            new Spec("findSpaces","查询指定停车场和楼层的空闲车位及余位统计",Map.of("parkingId",integer(true,Integer.MAX_VALUE),"floor",integer(true,100),"page",integer(false,10000))),
            new Spec("parkingRules","查询指定停车场的实际收费规则",Map.of("parkingId",integer(true,Integer.MAX_VALUE)))));
        if(a.role()==-1) list.addAll(List.of(
            new Spec("myAccount","查询本人可用余额、预留余额和会员期限",Map.of()),
            new Spec("myReservation","查询本人当前预约或在场状态",Map.of()),
            new Spec("myOrders","查询本人最近停车订单；默认最近7天，时间格式yyyy-MM-ddTHH:mm:ss，范围最多31天",Map.of("from",string(false,19),"to",string(false,19),"page",integer(false,10000))),
            new Spec("prepareReservation","仅生成预约确认卡片，未预约成功；必须由用户点击确认",Map.of("spaceId",integer(true,Integer.MAX_VALUE))),
            new Spec("prepareCancellation","仅生成本人预约取消确认卡片，未取消；必须由用户点击确认",Map.of("reservationId",integer(true,Integer.MAX_VALUE)))));
        else list.add(new Spec("managementSummary","查询权限范围内的经营统计，默认最近7天，最多31天",Map.of("parkingId",integer(false,Integer.MAX_VALUE),"days",integer(false,31))));
        return list;
    }
    public List<String> capabilities(AiActor actor) {return specs(actor).stream().map(Spec::name).toList();}
    public FunctionCallback[] callbacks(AiActor actor, Consumer<Map<String,Object>> events) {
        return specs(actor).stream().map(spec -> new FunctionCallback() {
            public String getName(){return spec.name;}
            public String getDescription(){return spec.description;}
            public String getInputTypeSchema(){
                var root=json.createObjectNode();root.put("type","object");root.put("additionalProperties",false);
                var props=root.putObject("properties");var required=root.putArray("required");
                spec.fields.forEach((name,f)->{var prop=props.putObject(name);prop.put("type",f.type);if(f.type.equals("integer")){prop.put("minimum",1);prop.put("maximum",f.max);}else{prop.put("minLength",1);prop.put("maxLength",f.max);}if(f.required)required.add(name);});
                return root.toString();
            }
            public String call(String input){
                try {
                    var args=validate(spec,input);
                    events.accept(Map.of("type","progress","message","正在查询业务数据"));
                    Object result=actor.scoped(()->execute(spec.name,args,actor,events));
                    return json.writeValueAsString(Map.of("ok",true,"data",result));
                } catch(Invalid e){return error(e.code,e.field,e.getMessage());}
                catch(org.springframework.dao.DataAccessException e){return error("UNAVAILABLE","","业务查询暂不可用，请稍后重试");}
                catch(Exception e){return error("UNAVAILABLE","","查询未完成，请稍后重试");}
            }
        }).toArray(FunctionCallback[]::new);
    }
    private String error(String code,String field,String message) {
        var n=json.createObjectNode();n.put("ok",false);n.put("code",code);n.put("field",field);n.put("message",message);return n.toString();
    }
    private ObjectNode validate(Spec spec,String raw) {
        if(raw==null || raw.length()>8192) throw new Invalid("INVALID_ARGUMENT","","参数为空或过长");
        JsonNode node;try{node=json.readTree(raw);}catch(Exception e){throw new Invalid("INVALID_ARGUMENT","","参数必须为合法JSON对象");}
        if(!(node instanceof ObjectNode obj))throw new Invalid("INVALID_ARGUMENT","","参数必须为JSON对象");
        obj.fieldNames().forEachRemaining(k->{if(!spec.fields.containsKey(k))throw new Invalid("INVALID_ARGUMENT",k,"不接受此参数");});
        spec.fields.forEach((k,f)->{
            var v=obj.get(k);if(v==null){if(f.required)throw new Invalid("INVALID_ARGUMENT",k,"缺少必填参数");return;}
            boolean valid=f.type.equals("integer") ? v.isIntegralNumber() && v.canConvertToInt() && v.intValue()>0 && v.intValue()<=f.max
                : v.isTextual() && !v.textValue().isBlank() && v.textValue().length()<=f.max;
            if(!valid)throw new Invalid("INVALID_ARGUMENT",k,"参数类型或范围无效");
        });return obj;
    }
    private int number(ObjectNode n,String k,int fallback){return n.has(k)?n.get(k).intValue():fallback;}
    private String scope(AiActor a,List<Object> args){if(a.role()!=0)return "";args.add(a.id());return " and p.admin_id=?";}
    private Map<String,Object> park(AiActor actor,int id) {
        var args=new ArrayList<Object>(List.of(id));
        var rows=db.queryForList("select p.id,p.park_name,p.park_address,p.park_storey from parking p where p.id=?"+scope(actor,args),args.toArray());
        if(rows.isEmpty())throw new Invalid("NOT_FOUND_OR_FORBIDDEN","parkingId","停车场不存在或无权访问");return rows.get(0);
    }
    private List<Map<String,Object>> current(AiActor a){return db.queryForList("select e.id,e.park_id,e.space_id,e.flag,e.reservation,e.expires_at,p.park_name,s.spaces_name,s.spaces_storey from enter e join parking p on p.id=e.park_id join spaces s on s.id=e.space_id where e.active_user_id=?",a.id());}
    private Object execute(String name,ObjectNode n,AiActor a,Consumer<Map<String,Object>> events) {
        int page=number(n,"page",1),offset=(page-1)*20;
        switch(name){
            case "findParking": {
                String keyword=n.path("keyword").asText("");var args=new ArrayList<Object>(List.of("%"+keyword+"%","%"+keyword+"%"));
                String filter=scope(a,args);args.add(offset);
                return db.queryForList("select p.id,p.park_name,p.park_address,p.park_storey from parking p where (p.park_name like ? or p.park_address like ?)"+filter+" order by p.id limit 20 offset ?",args.toArray());
            }
            case "findSpaces": {
                int id=n.get("parkingId").intValue(),floor=n.get("floor").intValue();var park=park(a,id);
                if(floor>((Number)park.get("park_storey")).intValue())throw new Invalid("INVALID_ARGUMENT","floor","楼层不存在");
                var args=new ArrayList<Object>(List.of(id,floor));String filter=scope(a,args);
                var counts=db.queryForMap("select count(*) total,coalesce(sum(s.spaces_flag=0 and s.active_enter_id is null),0) available from spaces s join parking p on p.id=s.park_id where s.park_id=? and s.spaces_storey=? and s.spaces_flag in (0,1)"+filter,args.toArray());
                args.add(offset);
                return Map.of("parking",park,"counts",counts,"page",page,"spaces",db.queryForList("select s.id,s.spaces_name,s.spaces_storey,s.spaces_row,s.spaces_col from spaces s join parking p on p.id=s.park_id where s.park_id=? and s.spaces_storey=? and s.spaces_flag=0 and s.active_enter_id is null"+filter+" order by s.id limit 20 offset ?",args.toArray()));
            }
            case "parkingRules": {
                int id=n.get("parkingId").intValue();park(a,id);
                var args=new ArrayList<Object>(List.of(id));String filter=scope(a,args);
                return db.queryForList("select r.rule_name,r.free_time,r.charging_unit,r.unit_cost from parking p join rule r on r.id=p.rule_id where p.id=?"+filter,args.toArray());
            }
            case "myAccount": {
                var now=LocalDateTime.now(clock);
                return db.queryForList("select balance-reserved_balance available_balance,reserved_balance,vip_start_time,vip_end_time,case when vip_start_time<=? and vip_end_time>? then 1 else 0 end membership_active from user where id=?",now,now,a.id());
            }
            case "myReservation":return current(a);
            case "myOrders": {
                if(n.has("from")!=n.has("to"))throw new Invalid("INVALID_ARGUMENT","from/to","开始和结束时间需一起提供");
                LocalDateTime to=LocalDateTime.now(clock),from=to.minusDays(7);
                try {if(n.has("from")){from=LocalDateTime.parse(n.get("from").textValue());to=LocalDateTime.parse(n.get("to").textValue());}}
                catch(Exception e){throw new Invalid("INVALID_ARGUMENT","from/to","日期格式无效");}
                if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0)throw new Invalid("INVALID_ARGUMENT","from/to","查询范围必须大于0且不超过31天");
                return db.queryForList("select o.id,p.park_name,o.enter_time,o.exit_time,o.parking_duration,o.moneys from orders o join parking p on p.id=o.park_id where o.user_id=? and o.create_time>=? and o.create_time<? order by o.id desc limit 20 offset ?",a.id(),from,to,offset);
            }
            case "managementSummary": {
                Integer parkId=n.has("parkingId")?n.get("parkingId").intValue():null;if(parkId!=null)park(a,parkId);
                return dashboard.summary(a.id(),a.role(),parkId,number(n,"days",7));
            }
            case "prepareReservation": {
                String plate=db.queryForObject("select plate_num from user where id=?",String.class,a.id());
                if(plate==null || !plate.matches(org.zjmproject.pojo.User.LICENSE_PLATE_REGEX))throw new Invalid("PROFILE_REQUIRED","spaceId","请先在个人资料中完善有效车牌信息");
                if(!current(a).isEmpty())throw new Invalid("CONFLICT","spaceId","您已有预约或车辆在场");
                var rows=db.queryForList("select s.id spaceId,p.id parkingId,p.park_name parkingName,s.spaces_name spaceName,s.spaces_storey floor from spaces s join parking p on p.id=s.park_id where s.id=? and s.spaces_flag=0 and s.active_enter_id is null",n.get("spaceId").intValue());
                if(rows.isEmpty())throw new Invalid("CONFLICT","spaceId","车位不存在或已不可预约");
                var card=new LinkedHashMap<String,Object>(rows.get(0));card.put("action","reserve");card.put("status","AWAITING_CONFIRMATION");
                events.accept(Map.of("type","action","data",card));return card;
            }
            case "prepareCancellation": {
                int id=n.get("reservationId").intValue();var rows=current(a);
                var entry=rows.stream().filter(e->((Number)e.get("id")).intValue()==id && ((Number)e.get("flag")).intValue()==0 && ((Number)e.get("reservation")).intValue()==1).findFirst();
                if(entry.isEmpty())throw new Invalid("NOT_FOUND_OR_FORBIDDEN","reservationId","没有可取消的本人预约");
                var e=entry.get();var card=new LinkedHashMap<String,Object>();card.put("action","cancel");card.put("reservationId",id);card.put("parkingName",e.get("park_name"));card.put("spaceName",e.get("spaces_name"));card.put("floor",e.get("spaces_storey"));card.put("status","AWAITING_CONFIRMATION");events.accept(Map.of("type","action","data",card));return card;
            }
            default:throw new Invalid("UNKNOWN_TOOL","function","不支持此函数");
        }
    }
    private static class Invalid extends RuntimeException {
        final String code,field;Invalid(String code,String field,String message){super(message);this.code=code;this.field=field;}
    }
}
