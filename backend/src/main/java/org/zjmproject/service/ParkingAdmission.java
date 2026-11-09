package org.zjmproject.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Wait before borrowing a JDBC connection; database locks still enforce all invariants. */
// 车辆写入的准入边界：先等待许可，再开启数据库事务，避免排队请求占满连接池。
@Component
public class ParkingAdmission {
    private final Semaphore gate;
    private final java.util.concurrent.atomic.AtomicInteger waiting=new java.util.concurrent.atomic.AtomicInteger();
    private final int permits;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    public ParkingAdmission(PlatformTransactionManager manager,MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${parking.admission.permits:8}") int configuredPermits,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.hikari.maximum-pool-size:20}") int poolSize) {
        this.transaction=new TransactionTemplate(manager);this.metrics=metrics;
        // Row locks and unique constraints protect writes. Fresh reads after the user lock
        // must observe cancellation that completed while an expiry worker was waiting.
        // 使用 READ COMMITTED，等待用户锁后能读到其他事务刚提交的取消或入场结果；一致性仍由锁与约束保证。
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        if(configuredPermits<0 || configuredPermits>Math.max(1,poolSize-4))throw new IllegalArgumentException("Admission permits must leave at least four JDBC connections available; zero disables the gate for experiments");
        permits=configuredPermits;
        // 公平信号量限制全局在途写事务，预留连接给读取和后台任务；不是按停车场分组互斥。
        gate=new Semaphore(permits,true);
    }
    public static class Busy extends IllegalStateException {public Busy(){super("停车场请求较多，请稍后重试");}}
    public <T> T execute(Integer parkingId,Supplier<T> action) {
        // 拒绝外层已有事务，防止调用者提前借用 JDBC 连接并绕过准入。
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Vehicle operations must start outside an existing transaction");
        if(permits==0)return run(action);
        // 限制等待请求数量与等待时长；拒绝单独计数，不能计作成功业务吞吐。
        if(waiting.incrementAndGet()>128){waiting.decrementAndGet();metrics.counter("parking.admission.rejected","reason","capacity").increment();throw new Busy();}
        boolean locked=false;long start=System.nanoTime();
        try {
            locked=gate.tryAcquire(5,TimeUnit.SECONDS);
            if(!locked){metrics.counter("parking.admission.rejected","reason","timeout").increment();throw new Busy();}
            metrics.timer("parking.admission.wait").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
            waiting.decrementAndGet();
            return run(action);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Busy();}
        finally{if(locked)gate.release();else waiting.decrementAndGet();}
    }
    private <T> T run(Supplier<T> action) {
        long start=System.nanoTime();
        try{for(int attempt=0;;attempt++){try{return transaction.execute(status->{
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                private long before;
                // 记录 Spring 提交回调之间的应用耗时窗口，不等同于 MySQL 精确提交或持锁耗时。
                @Override public void beforeCommit(boolean readOnly){before=System.nanoTime();}
                @Override public void afterCommit(){if(before!=0)metrics.timer("parking.flow.commit.window").record(System.nanoTime()-before,TimeUnit.NANOSECONDS);}
            });
            return action.get();
        });}catch(org.springframework.dao.DataAccessException failure){
            // Retry only MySQL's definite deadlock rollback, never ambiguous connection/commit failures.
            // 仅对确认已整体回滚的 MySQL 1213 死锁做最多两次重试；提交结果不明时不能盲目重放。
            if(attempt>=2 || !deadlock(failure))throw failure;
            metrics.counter("parking.flow.transaction.retry","reason","deadlock").increment();
            try{TimeUnit.MILLISECONDS.sleep((1L<<attempt)*10+java.util.concurrent.ThreadLocalRandom.current().nextInt(10));}
            catch(InterruptedException stop){Thread.currentThread().interrupt();throw new Busy();}
        }}}
        finally{metrics.timer("parking.flow.transaction.duration").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    private boolean deadlock(Throwable error) {
        for(int depth=0;error!=null && depth<32;depth++,error=error.getCause())
            if(error instanceof java.sql.SQLException sql && sql.getErrorCode()==1213)return true;
        return false;
    }
}
