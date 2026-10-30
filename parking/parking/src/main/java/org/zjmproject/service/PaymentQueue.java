package org.zjmproject.service;

import jakarta.annotation.PreDestroy;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.zjmproject.config.QueueConfig;
import org.zjmproject.observability.RuntimeEvents;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SQL holds the delivery intent; Redis messages carry only an ID and a generation. */
@Component
public class PaymentQueue {
    public static final String READY="parking:{payment}:ready",PROCESSING="parking:{payment}:processing";
    private final RedissonClient redis;
    private final JdbcTemplate db;
    private final PaymentService payments;
    private final Clock clock;
    private final ScheduledExecutorService dispatch=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"payment-dispatch");t.setDaemon(true);return t;});
    private final ExecutorService consumer=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"payment-consumer");t.setDaemon(true);return t;});
    private final AtomicBoolean recovering=new AtomicBoolean();
    private volatile boolean running;
    public PaymentQueue(RedissonClient redis,JdbcTemplate db,PaymentService payments,Clock clock,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.redis=redis;this.db=db;this.payments=payments;this.clock=clock;
        io.micrometer.core.instrument.Gauge.builder("parking.payment.pending",db,x->x.queryForObject("select count(*) from payment_event where completed_at is null",Long.class)).register(metrics);
    }
    @TransactionalEventListener public void scheduled(PaymentService.Scheduled event) {schedule(event.id(),0);}
    private void schedule(int id,int attempt) {
        if(!dispatch.isShutdown()) dispatch.schedule(()->{
            try {deliver(id);} catch(Exception e) {warn("payment.dispatch",id,e);schedule(id,attempt+1);}
        },attempt==0?0:Math.min(30,1L<<Math.min(5,attempt)),TimeUnit.SECONDS);
    }
    public void deliver(int id) {
        var rows=db.queryForList("select * from payment_event where recharge_id=? and completed_at is null",id);
        if(rows.isEmpty()) return;var row=rows.get(0);
        var at=row.get("next_run_at") instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)row.get("next_run_at");
        String value=id+":"+row.get("version");
        redis.getDelayedQueue(redis.<String>getBlockingDeque(READY)).offer(value,Math.max(0,Duration.between(LocalDateTime.now(clock),at).toMillis()),TimeUnit.MILLISECONDS);
        db.update("update payment_event set delivered_at=? where recharge_id=? and version=?",LocalDateTime.now(clock),id,row.get("version"));
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        if(!payments.enabled()) return;
        running=true;redis.getDelayedQueue(redis.<String>getBlockingDeque(READY));
        recover();consumer.submit(this::consume);
    }
    @EventListener(QueueConfig.RedisConnected.class) public void connected() {if(running) recover();}
    private void recover() {
        if(!recovering.compareAndSet(false,true)) return;
        dispatch.execute(()->{
            try {
                payments.recoverLegacy();
                var processing=redis.<String>getDeque(PROCESSING);int count=processing.size();
                for(int i=0;i<count && running;i++) if(processing.pollLastAndOfferFirstTo(READY)==null) break;
                int after=0;
                while(running) {
                    var ids=db.queryForList("select recharge_id from payment_event where completed_at is null and recharge_id>? order by recharge_id limit 500",Integer.class,after);
                    if(ids.isEmpty()) break;
                    for(int id:ids) deliver(id);after=ids.get(ids.size()-1);
                }
                RuntimeEvents.event("payment.recovery","SUCCESS",null);
            } catch(Exception e) {warn("payment.recovery",null,e);dispatch.schedule(this::recover,5,TimeUnit.SECONDS);}
            finally {recovering.set(false);}
        });
    }
    private void consume() {
        var ready=redis.<String>getBlockingDeque(READY);var processing=redis.<String>getDeque(PROCESSING);
        while(running && !Thread.currentThread().isInterrupted()) {
            try(var ignored=RuntimeEvents.scope(null)) {
                String value=ready.pollLastAndOfferFirstTo(PROCESSING,5,TimeUnit.SECONDS);
                if(value==null) continue;
                if(!value.matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,18}")) {
                    warn("payment.invalid-message",null,new IllegalArgumentException());processing.removeFirstOccurrence(value);continue;
                }
                String[] parts=value.split(":");int id;long version;
                try {id=Integer.parseInt(parts[0]);version=Long.parseLong(parts[1]);}
                catch(NumberFormatException invalid) {warn("payment.invalid-message",null,invalid);processing.removeFirstOccurrence(value);continue;}
                try {
                    var rows=db.queryForList("select request_id,version,completed_at from payment_event where recharge_id=?",id);
                    if(rows.isEmpty() || rows.get(0).get("completed_at")!=null || ((Number)rows.get(0).get("version")).longValue()!=version) {
                        processing.removeFirstOccurrence(value);continue;
                    }
                    String requestId=rows.isEmpty()?null:(String)rows.get(0).get("request_id");
                    try(var context=RuntimeEvents.scope(requestId)) {payments.process(id,version,false);}
                    // Persist next intent first, deliver it, then acknowledge the current copy.
                    deliver(id);processing.removeFirstOccurrence(value);
                } catch(Exception e) {
                    warn("payment.consume",id,e);
                    redis.getDelayedQueue(ready).offer(value,5,TimeUnit.SECONDS);
                    processing.removeFirstOccurrence(value);
                }
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            catch(Exception e) {
                warn("payment.connection",null,e);
                try {Thread.sleep(2000);} catch(InterruptedException stop) {Thread.currentThread().interrupt();}
                if(running) recover();
            }
        }
    }
    private static void warn(String event,Integer id,Exception e) {
        org.slf4j.LoggerFactory.getLogger(PaymentQueue.class).atWarn().addKeyValue("event",event)
            .addKeyValue("entityId",id).addKeyValue("result","RETRY").addKeyValue("reasonCode",e.getClass().getSimpleName()).log("payment_queue_retry");
    }
    @PreDestroy public void stop() {running=false;consumer.shutdownNow();dispatch.shutdownNow();}
}
