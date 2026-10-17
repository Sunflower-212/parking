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
@Component
public class ParkingAdmission {
    private final Semaphore[] stripes=new Semaphore[64];
    private final int permits;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    public ParkingAdmission(PlatformTransactionManager manager,MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${parking.admission.permits:4}") int configuredPermits,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.hikari.maximum-pool-size:20}") int poolSize) {
        this.transaction=new TransactionTemplate(manager);this.metrics=metrics;
        if(configuredPermits<1 || configuredPermits>8)throw new IllegalArgumentException("Admission permits must be between 1 and 8");
        permits=Math.min(configuredPermits,Math.max(1,poolSize/4));
        for(int i=0;i<stripes.length;i++)stripes[i]=new Semaphore(permits,true);
    }
    public static class Busy extends IllegalStateException {public Busy(){super("停车场请求较多，请稍后重试");}}
    public <T> T execute(Integer parkingId,Supplier<T> action) {
        // Never acquire an application gate while an outer transaction could already hold rows.
        if(TransactionSynchronizationManager.isActualTransactionActive())return action.get();
        if(parkingId==null)return transaction.execute(status->action.get());
        Semaphore gate=stripes[Math.floorMod(parkingId,stripes.length)];
        if(gate.getQueueLength()>=128)throw new Busy();
        boolean locked=false;long start=System.nanoTime();
        try {
            locked=gate.tryAcquire(5,TimeUnit.SECONDS);
            if(!locked)throw new Busy();
            metrics.timer("parking.admission.wait").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
            return transaction.execute(status->action.get());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Busy();}
        finally{if(locked)gate.release();}
    }
}
