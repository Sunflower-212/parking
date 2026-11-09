package org.zjmproject.service;

import jakarta.annotation.PreDestroy;
import org.redisson.api.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.zjmproject.config.QueueConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-backed delay delivery, durable SQL outbox, and atomic ready -> processing claim. */
@Component
public class ReservationQueue {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReservationQueue.class);
    private static final String READY="parking:{reservation}:ready", PROCESSING="parking:{reservation}:processing";
    private final RedissonClient redis;
    private final JdbcTemplate db;
    private final ParkingFlowService flow;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final ScheduledExecutorService dispatch=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"reservation-dispatch");t.setDaemon(true);return t;});
    private final ExecutorService consumer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"reservation-consumer");t.setDaemon(true);return t;});
    private final AtomicBoolean recoveryQueued=new AtomicBoolean();
    private volatile boolean running;
    public ReservationQueue(RedissonClient redis,JdbcTemplate db,ParkingFlowService flow,Clock clock,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.redis=redis;this.db=db;this.flow=flow;this.clock=clock;this.metrics=metrics;
        io.micrometer.core.instrument.Gauge.builder("parking.reservation.outbox.pending",db,x->x.queryForObject("select count(*) from reservation_event where completed_at is null",Long.class)).register(metrics);
    }
    @TransactionalEventListener
    public void created(ParkingFlowService.ReservationCreated event) { schedule(event.id(),0); }
    private void schedule(int id,int attempt) {
        if(!dispatch.isShutdown()) dispatch.schedule(()->deliver(id,attempt),Math.min(30,attempt==0?0:1L<<Math.min(attempt,5)),TimeUnit.SECONDS);
    }
    private void deliver(int id,int attempt) {
        try(var context=org.zjmproject.observability.RuntimeEvents.scope(null)) {
        try {
            var rows=db.queryForList("select expires_at,request_id from reservation_event where reservation_id=? and completed_at is null",id);
            if(rows.isEmpty()) return;
            setCorrelation(rows.get(0).get("request_id"));
            Object expiry=rows.get(0).get("expires_at");
            LocalDateTime due=expiry instanceof java.sql.Timestamp timestamp?timestamp.toLocalDateTime():(LocalDateTime)expiry;
            long millis=Math.max(0,Duration.between(LocalDateTime.now(clock),due).toMillis());
            redis.getDelayedQueue(redis.<String>getBlockingDeque(READY)).offer(Integer.toString(id),millis,TimeUnit.MILLISECONDS);
            db.update("update reservation_event set delivered_at=now(),last_error=null where reservation_id=?",id);
        } catch(Exception e) {
            metrics.counter("parking.reservation.dispatch.failure").increment();
            log.atWarn().addKeyValue("event","reservation.dispatch").addKeyValue("result","RETRY")
                .addKeyValue("entityId",id).addKeyValue("attempt",attempt).addKeyValue("reasonCode",e.getClass().getSimpleName()).log("delivery_failed");
            try {db.update("update reservation_event set attempts=attempts+1,last_error=? where reservation_id=?",e.getClass().getSimpleName(),id);} catch(Exception ignored) { }
            schedule(id,attempt+1);
        }
        }
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running=true;
        // Creating the delayed queue also restores Redisson's transfer service after restart.
        redis.getDelayedQueue(redis.<String>getBlockingDeque(READY));
        recover(); consumer.submit(this::consume);
    }
    @EventListener(QueueConfig.RedisConnected.class)
    public void reconnected() { if(running) recover(); }
    private void recover() {
        if(!recoveryQueued.compareAndSet(false,true)) return;
        dispatch.execute(()->{
            try {
                RDeque<String> processing=redis.getDeque(PROCESSING);
                // 本次只恢复进入校准时已在处理队列中的消息，避免同时新领取的消息使恢复循环无界增长。
                int interrupted=processing.size();
                for(int recovered=0;recovered<interrupted && running;recovered++) {
                    if(processing.pollLastAndOfferFirstTo(READY)==null) break;
                }
                // 启动或 Redis 重连时按主键游标分批恢复事件；不是定时扫描预约表判断过期。
                int after=0;
                while(running) {
                    var batch=db.queryForList("select reservation_id from reservation_event where completed_at is null and reservation_id>? order by reservation_id limit 500",Integer.class,after);
                    if(batch.isEmpty()) break;
                    for(int id:batch) deliver(id,0);
                    after=batch.get(batch.size()-1);
                }
                log.atInfo().addKeyValue("event","reservation.recovery").addKeyValue("result","SUCCESS").log("recovery_finished");
            } catch(Exception e) {
                log.warn("Reservation recovery failed ({})",e.getClass().getSimpleName());
                dispatch.schedule(this::recover,5,TimeUnit.SECONDS);
            } finally {recoveryQueued.set(false);}
        });
    }
    private void consume() {
        RBlockingDeque<String> ready=redis.getBlockingDeque(READY);
        RDeque<String> processing=redis.getDeque(PROCESSING);
        while(running && !Thread.currentThread().isInterrupted()) {
            try(var context=org.zjmproject.observability.RuntimeEvents.scope(null)) {
            try {
                String value=ready.pollLastAndOfferFirstTo(PROCESSING,5,TimeUnit.SECONDS);
                if(value==null) continue;
                try {
                    int id=Integer.parseInt(value);
                    // Completion is monotonic. A committed cancellation/entry/expiry needs no
                    // user lock or write transaction when its delayed copy finally arrives.
                    // Missing ledger rows still use the normal DB-state validation path.
                    // 事件完成状态单向变化，已完成的延迟副本可直接确认，避免无效消息进入写事务。
                    var completed=db.queryForList("select completed_at,request_id from reservation_event where reservation_id=?",id);
                    if(!completed.isEmpty())setCorrelation(completed.get(0).get("request_id"));
                    if(!completed.isEmpty() && completed.get(0).get("completed_at")!=null) {
                        processing.removeFirstOccurrence(value);
                        metrics.counter("parking.reservation.completed.skip").increment();
                        org.zjmproject.observability.RuntimeEvents.event("reservation.expire","SKIPPED",id);
                        continue;
                    }
                    flow.expire(id); processing.removeFirstOccurrence(value);
                }
                catch(Exception e) {
                    metrics.counter("parking.reservation.processing.failure").increment();
                    // 队列内容不作为日志文本输出；只保留受限异常类型，防止损坏消息污染日志。
                    log.atWarn().addKeyValue("event","reservation.consume").addKeyValue("result","RETRY")
                        .addKeyValue("reasonCode",e.getClass().getSimpleName()).log("processing_failed");
                    // Retry is persisted before acknowledging the processing copy.
                    redis.getDelayedQueue(ready).offer(value,5,TimeUnit.SECONDS);
                    processing.removeFirstOccurrence(value);
                    db.update("update reservation_event set attempts=attempts+1,last_error=? where reservation_id=?",e.getClass().getSimpleName(),Integer.parseInt(value));
                }
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(Exception e) {
                log.warn("Reservation consumer reconnecting ({})",e.getClass().getSimpleName());
                try {TimeUnit.SECONDS.sleep(2);} catch(InterruptedException stop) {Thread.currentThread().interrupt();}
                if(running) recover();
            }
            }
        }
    }
    private void setCorrelation(Object id) {if(id instanceof String s && s.matches("[a-zA-Z0-9-]{1,64}"))org.slf4j.MDC.put("requestId",s);}
    @PreDestroy public void stop() {running=false;consumer.shutdownNow();dispatch.shutdownNow();}
}
