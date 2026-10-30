package org.zjmproject.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.zjmproject.observability.RuntimeEvents;
import org.zjmproject.pojo.FormContent;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PaymentService {
    public record Scheduled(int id,long version) {}
    public record Status(int id, BigDecimal amounts, String paymentState, LocalDateTime expiresAt, boolean canCheck) {}
    private record Work(int id,long version,String token,Map<String,Object> charge,int attempts) {}
    private final JdbcTemplate db;
    private final AliPayService alipay;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final boolean enabled;
    private final String appId;
    private final Duration timeout;
    public PaymentService(JdbcTemplate db,AliPayService alipay,PlatformTransactionManager manager,
            ApplicationEventPublisher events,Clock clock,
            @Value("${parking.integrations.alipay:false}") boolean enabled,
            @Value("${alipay.app-id:}") String appId,
            @Value("${parking.payment-timeout:30m}") Duration timeout) {
        this.db=db;this.alipay=alipay;this.tx=manager==null?null:new TransactionTemplate(manager);
        this.events=events;this.clock=clock;this.enabled=enabled;this.appId=appId;this.timeout=timeout;
        if(timeout.compareTo(Duration.ofMinutes(1))<0 || timeout.compareTo(Duration.ofDays(15))>0)
            throw new IllegalArgumentException("付款有效期需为1分钟至15天");
    }
    private LocalDateTime now() {return LocalDateTime.now(clock);}
    public boolean enabled() {return enabled;}
    private void requireEnabled() {if(!enabled) throw new IllegalStateException("支付宝尚未启用，请配置支付宝服务");}
    private static LocalDateTime time(Object value) {
        return value==null?null:value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value;
    }
    private static int number(Object value) {return ((Number)value).intValue();}
    private String orderNo(Map<String,Object> charge) {return Objects.toString(charge.get("merchant_order_no"),charge.get("id").toString());}
    private Map<String,Object> charge(int id) {
        var rows=db.queryForList("select * from recharges where id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("充值记录不存在");
        return rows.get(0);
    }
    private Map<String,Object> owned(int userId,int id) {
        var row=charge(id);
        if(number(row.get("user_id"))!=userId) throw new IllegalArgumentException("充值记录不存在");
        return row;
    }
    public Status status(int userId,int id) {return status(owned(userId,id));}
    private Status status(Map<String,Object> row) {
        String state=number(row.get("flag"))==1?"PAID":Objects.toString(row.get("payment_state"),"VERIFYING");
        return new Status(number(row.get("id")),new BigDecimal(row.get("amounts").toString()),state,time(row.get("expires_at")),!Set.of("PAID","CLOSED").contains(state));
    }
    private void enqueue(int id) {
        var row=db.queryForMap("select version from payment_event where recharge_id=?",id);
        events.publishEvent(new Scheduled(id,((Number)row.get("version")).longValue()));
    }
    private void ensureEvent(int id) {
        db.update("insert ignore into payment_event(recharge_id,next_run_at,request_id) values(?,?,?)",id,now(),RuntimeEvents.requestId());
    }
    public FormContent<String> create(int userId,BigDecimal amount,int method) throws Exception {
        requireEnabled();
        if(method!=0) throw new IllegalArgumentException("当前仅支持支付宝");
        if(amount==null || amount.signum()<=0 || amount.scale()>2 || amount.compareTo(new BigDecimal("1000000"))>0)
            throw new IllegalArgumentException("充值金额需为0.01至1000000，最多两位小数");
        int id=Objects.requireNonNull(tx.execute(s->{
            var keys=new GeneratedKeyHolder();var created=now().withNano(0);var expires=created.plus(timeout).withNano(0);
            // Provider order numbers must survive database rebuilds and separate sandbox environments.
            String merchant="PK"+UUID.randomUUID().toString().replace("-","");
            db.update(c->{var p=c.prepareStatement("insert into recharges(user_id,amounts,pay_method,create_time,merchant_order_no,expires_at,payment_state) values(?,?,0,?,?,?,'PENDING')",Statement.RETURN_GENERATED_KEYS);
                p.setInt(1,userId);p.setBigDecimal(2,amount);p.setObject(3,created);p.setString(4,merchant);p.setObject(5,expires);return p;},keys);
            int value=Objects.requireNonNull(keys.getKey()).intValue();
            db.update("insert into payment_event(recharge_id,next_run_at,request_id) values(?,?,?)",value,created.plusSeconds(30),RuntimeEvents.requestId());
            enqueue(value);return value;
        }));
        // SDK page signing and all provider requests run outside database transactions.
        return alipay.pay(id);
    }
    public Status cancel(int userId,int id) {
        requireEnabled(); owned(userId,id);
        tx.executeWithoutResult(s->{
            var row=lockCharge(id);
            if(number(row.get("flag"))==1 || "CLOSED".equals(row.get("payment_state"))) return;
            ensureEvent(id);
            db.update("update recharges set cancel_requested=true,payment_state='CLOSING' where id=?",id);
            db.update("update payment_event set version=version+1,next_run_at=?,completed_at=null,lease_token=null,lease_until=null,request_id=? where recharge_id=?",now(),RuntimeEvents.requestId(),id);
            enqueue(id);
        });
        return check(userId,id);
    }
    public Status check(int userId,int id) {
        requireEnabled();var row=owned(userId,id);
        if(!status(row).canCheck()) return status(row);
        tx.executeWithoutResult(s->{
            ensureEvent(id);
            db.update("update payment_event set version=version+1,completed_at=null,next_run_at=? where recharge_id=? and completed_at is not null",now(),id);
            enqueue(id);
        });
        process(id,null,true);
        return status(userId,id);
    }
    private Map<String,Object> lockCharge(int id) {
        var original=charge(id);
        var users=db.queryForList("select id from user where id=? for update",original.get("user_id"));
        if(users.isEmpty()) throw new IllegalStateException("充值用户不存在");
        return db.queryForMap("select * from recharges where id=? for update",id);
    }
    /** Called only after notification signature verification; return parameters are never passed here. */
    public void confirm(Map<String,String> params) {
        requireEnabled();
        if(appId.isBlank() || !appId.equals(params.get("app_id"))) throw new IllegalArgumentException("支付应用不匹配");
        String no=params.get("out_trade_no");
        if(no==null || !no.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("支付订单号无效");
        var rows=db.queryForList("select id from recharges where merchant_order_no=? or (merchant_order_no is null and cast(id as char)=?)",no,no);
        if(rows.size()!=1) throw new IllegalArgumentException("充值记录不存在");
        int id=number(rows.get(0).get("id"));String state=params.get("trade_status");
        if(Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(Objects.toString(state,""))) {
            var paidAt=LocalDateTime.parse(params.get("gmt_payment"),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            credit(id,new PaymentTrade(no,params.get("trade_no"),state,new BigDecimal(params.get("total_amount")),paidAt));
        } else if("TRADE_CLOSED".equals(state)) {
            tx.executeWithoutResult(s->{var row=lockCharge(id);if(number(row.get("flag"))!=1) {
                if(params.get("total_amount")==null || new BigDecimal(row.get("amounts").toString()).compareTo(new BigDecimal(params.get("total_amount")))!=0)
                    throw new IllegalArgumentException("支付金额不匹配");
                db.update("update recharges set flag=2,payment_state='CLOSED' where id=?",id);complete(id);
            }});
        }
    }
    private void validatePaid(Map<String,Object> row,PaymentTrade trade) {
        if(!trade.paid() || !orderNo(row).equals(trade.orderNo()) || trade.amount()==null ||
                trade.amount().signum()<=0 || new BigDecimal(row.get("amounts").toString()).compareTo(trade.amount())!=0 ||
                number(row.get("pay_method"))!=0 || trade.tradeNo()==null || trade.tradeNo().isBlank() || trade.tradeNo().length()>64 || trade.paidAt()==null)
            throw new IllegalArgumentException("支付信息不匹配");
        // A numeric historical order may collide with another environment. Do not credit an older payment.
        if(trade.paidAt().isBefore(time(row.get("create_time")).minusMinutes(1))) throw new IllegalArgumentException("支付时间不匹配");
    }
    private void credit(int id,PaymentTrade trade) {
        tx.executeWithoutResult(s->{
            var row=lockCharge(id);validatePaid(row,trade);
            if(number(row.get("flag"))==1) {
                if(!Objects.equals(row.get("pay_no"),trade.tradeNo())) throw new IllegalArgumentException("支付流水不匹配");
                complete(id);RuntimeEvents.committed("payment.confirm","SKIPPED",id);return;
            }
            if(db.update("update user set balance=balance+?,update_time=now() where id=?",trade.amount(),row.get("user_id"))!=1) throw new IllegalStateException("充值用户不存在");
            db.update("update recharges set flag=1,payment_state='PAID',pay_no=?,pay_time=? where id=?",trade.tradeNo(),trade.paidAt(),id);
            complete(id);RuntimeEvents.committed("payment.confirm","SUCCESS",id);
        });
    }
    private void complete(int id) {
        db.update("update payment_event set version=version+1,completed_at=?,lease_token=null,lease_until=null,last_error=null where recharge_id=? and completed_at is null",now(),id);
    }
    private Work claim(int id,Long expected,boolean manual) {
        return tx.execute(s->{
            var rows=db.queryForList("select * from payment_event where recharge_id=? for update",id);
            if(rows.isEmpty()) return null;var event=rows.get(0);var at=now();
            long version=((Number)event.get("version")).longValue();
            if(event.get("completed_at")!=null || expected!=null && expected!=version) return null;
            if(event.get("lease_until")!=null && time(event.get("lease_until")).isAfter(at)) return null;
            if(event.get("last_checked_at")!=null && time(event.get("last_checked_at")).plusSeconds(10).isAfter(at)) {
                db.update("update payment_event set next_run_at=? where recharge_id=?",time(event.get("last_checked_at")).plusSeconds(10),id);return null;
            }
            if(!manual && time(event.get("next_run_at")).isAfter(at)) return null;
            String token=UUID.randomUUID().toString();
            db.update("update payment_event set lease_token=?,lease_until=?,next_run_at=?,last_checked_at=? where recharge_id=?",token,at.plusSeconds(30),at.plusSeconds(30),at,id);
            return new Work(id,version,token,charge(id),number(event.get("attempts")));
        });
    }
    public void process(int id,Long version,boolean manual) {
        if(!enabled) return;
        Work work=claim(id,version,manual);if(work==null) return;
        var row=work.charge();
        try {
            if(number(row.get("flag"))==1) {tx.executeWithoutResult(s->complete(id));return;}
            String no=orderNo(row);PaymentTrade trade=alipay.query(no);
            if(trade==null || !no.equals(trade.orderNo())) throw new IllegalArgumentException("支付信息不匹配");
            if(trade.paid()) {credit(id,trade);return;}
            var expiry=time(row.get("expires_at"));
            boolean cancel=Boolean.TRUE.equals(row.get("cancel_requested")) || row.get("cancel_requested") instanceof Number n && n.intValue()!=0;
            boolean expired=expiry!=null && !now().isBefore(expiry);
            if("WAIT_BUYER_PAY".equals(trade.status()) && (cancel || expired)) {
                if(alipay.close(no)) trade=new PaymentTrade(no,null,"TRADE_CLOSED",null,null);
                else trade=alipay.query(no);
                if(trade==null || !no.equals(trade.orderNo())) throw new IllegalArgumentException("支付信息不匹配");
                if(trade.paid()) {credit(id,trade);return;}
            }
            if("TRADE_CLOSED".equals(trade.status()) || "NOT_FOUND".equals(trade.status()) && expired) {
                finish(work,"CLOSED",null,0,null);return;
            }
            if(!Set.of("WAIT_BUYER_PAY","NOT_FOUND").contains(trade.status())) throw new IllegalArgumentException("支付状态不明确");
            if(expiry==null && time(row.get("create_time")).isBefore(now().minusDays(15))) {
                finish(work,"REVIEW",null,0,"LEGACY_UNCONFIRMED");return;
            }
            String state=cancel || expired?"CLOSING":expiry==null?"VERIFYING":"PENDING";
            finish(work,state,next(row,now().plusSeconds(cancel || expired?15:60)),0,null);
        } catch(Exception e) {
            int errors=work.attempts()+1;
            String reason=e instanceof IllegalArgumentException?"PAYMENT_MISMATCH":e instanceof org.springframework.dao.DataAccessException?"DATABASE_FAILURE":"PROVIDER_UNAVAILABLE";
            finish(work,errors>=5 || "PAYMENT_MISMATCH".equals(reason)?"REVIEW":"VERIFYING",
                    next(row,now().plusSeconds(Math.min(300,5L<<Math.min(errors-1,6)))),errors,reason);
            org.slf4j.LoggerFactory.getLogger(PaymentService.class).atWarn().addKeyValue("event","payment.reconcile")
                .addKeyValue("entityId",id).addKeyValue("result","RETRY").addKeyValue("reasonCode",reason).log("payment_unconfirmed");
        }
    }
    private LocalDateTime next(Map<String,Object> row,LocalDateTime proposed) {
        var expiry=time(row.get("expires_at"));return expiry!=null && expiry.isAfter(now()) && expiry.isBefore(proposed)?expiry:proposed;
    }
    private void finish(Work work,String state,LocalDateTime due,int errors,String reason) {
        tx.executeWithoutResult(s->{
            var row=lockCharge(work.id());var event=db.queryForMap("select * from payment_event where recharge_id=? for update",work.id());
            if(number(row.get("flag"))==1 || !Objects.equals(event.get("lease_token"),work.token()) || ((Number)event.get("version")).longValue()!=work.version()) return;
            db.update("update recharges set payment_state=?,flag=case when ?='CLOSED' then 2 else flag end where id=?",state,state,work.id());
            db.update("update payment_event set version=version+1,next_run_at=?,completed_at=?,attempts=?,last_error=?,lease_token=null,lease_until=null where recharge_id=?",due==null?now():due,due==null?now():null,errors,reason,work.id());
            if(due!=null) enqueue(work.id());
            RuntimeEvents.committed("payment.reconcile",state,work.id());
        });
    }
    /** Only called at startup, not as a periodic business-table scan. */
    public void recoverLegacy() {
        if(!enabled) return;
        int after=0;
        while(true) {
            var ids=db.queryForList("select id from recharges where id>? and flag<>1 and pay_method=0 and create_time>=? order by id limit 500",Integer.class,after,now().minusDays(15));
            if(ids.isEmpty()) return;
            tx.executeWithoutResult(s->{for(int id:ids) ensureEvent(id);});after=ids.get(ids.size()-1);
        }
    }
}
