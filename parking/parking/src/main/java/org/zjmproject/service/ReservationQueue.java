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
        try {
            var rows=db.query("select expires_at from reservation_event where reservation_id=? and completed_at is null",(r,n)->r.getObject(1,LocalDateTime.class),id);
            if(rows.isEmpty()) return;
            long millis=Math.max(0,Duration.between(LocalDateTime.now(clock),rows.get(0)).toMillis());
            redis.getDelayedQueue(redis.<String>getBlockingDeque(READY)).offer(Integer.toString(id),millis,TimeUnit.MILLISECONDS);
            db.update("update reservation_event set delivered_at=now(),last_error=null where reservation_id=?",id);
        } catch(Exception e) {
            metrics.counter("parking.reservation.dispatch.failure").increment();
            log.warn("Reservation {} dispatch attempt {} failed ({})",id,attempt,e.getClass().getSimpleName());
            try {db.update("update reservation_event set attempts=attempts+1,last_error=? where reservation_id=?",e.getClass().getSimpleName(),id);} catch(Exception ignored) { }
            schedule(id,attempt+1);
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
                int interrupted=processing.size();
                for(int recovered=0;recovered<interrupted && running;recovered++) {
                    if(processing.pollLastAndOfferFirstTo(READY)==null) break;
                }
                int after=0;
                while(running) {
                    var batch=db.queryForList("select reservation_id from reservation_event where completed_at is null and reservation_id>? order by reservation_id limit 500",Integer.class,after);
                    if(batch.isEmpty()) break;
                    for(int id:batch) deliver(id,0);
                    after=batch.get(batch.size()-1);
                }
                log.info("Reservation recovery complete");
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
            try {
                String value=ready.pollLastAndOfferFirstTo(PROCESSING,5,TimeUnit.SECONDS);
                if(value==null) continue;
                try {
                    int id=Integer.parseInt(value);
                    // Completion is monotonic. A committed cancellation/entry/expiry needs no
                    // user lock or write transaction when its delayed copy finally arrives.
                    // Missing ledger rows still use the normal DB-state validation path.
                    var completed=db.queryForList("select completed_at is not null from reservation_event where reservation_id=?",Boolean.class,id);
                    if(!completed.isEmpty() && Boolean.TRUE.equals(completed.get(0))) {
                        processing.removeFirstOccurrence(value);
                        metrics.counter("parking.reservation.completed.skip").increment();
                        continue;
                    }
                    flow.expire(id); processing.removeFirstOccurrence(value);
                }
                catch(Exception e) {
                    metrics.counter("parking.reservation.processing.failure").increment();
                    log.warn("Reservation {} processing failed ({})",value,e.getClass().getSimpleName());
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
    @PreDestroy public void stop() {running=false;consumer.shutdownNow();dispatch.shutdownNow();}
}
