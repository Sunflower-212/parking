package org.zjmproject.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Disposable read cache. Never shares a connection factory or eviction policy with session/queue Redis. */
@Service
public class ReadCache {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReadCache.class);
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final Object[] stripes=new Object[64];
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate redis;
    private volatile long unavailableUntil;
    public ReadCache(ObjectMapper json,MeterRegistry metrics,
            @Value("${parking.cache.enabled:false}") boolean enabled,
            @Value("${parking.cache.host:localhost}") String host,
            @Value("${parking.cache.port:6379}") int port,
            @Value("${parking.cache.password:}") String password) {
        this.json=json;this.metrics=metrics;
        for(int i=0;i<stripes.length;i++) stripes[i]=new Object();
        if(enabled) {
            var config=new RedisStandaloneConfiguration(host,port);
            if(!password.isBlank()) config.setPassword(password);
            var options=ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build()).build();
            var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).clientOptions(options).build();
            factory=new LettuceConnectionFactory(config,client);factory.afterPropertiesSet();factory.start();
            redis=new StringRedisTemplate(factory);
        } else {factory=null;redis=null;}
    }
    private boolean available() {return redis!=null && System.currentTimeMillis()>=unavailableUntil;}
    private void failed(Exception e) {
        unavailableUntil=System.currentTimeMillis()+10000;
        metrics.counter("parking.cache.failure").increment();
        log.warn("Read cache unavailable; database fallback for 10s ({})",e.getClass().getSimpleName());
    }
    private String version(String resource) {
        String key="parking:cache:v1:revision:"+resource;
        String current=redis.opsForValue().get(key);
        if(current!=null) return current;
        redis.opsForValue().setIfAbsent(key,UUID.randomUUID().toString());
        // Random revisions cannot resurrect an old entry if LFU evicts the revision key.
        current=redis.opsForValue().get(key);
        if(current==null) throw new IllegalStateException("Cache revision unavailable");
        return current;
    }
    public <T> T get(String resource,String suffix,Duration ttl,TypeReference<T> type,Supplier<T> loader) {
        if(!available()) return loader.get();
        String key;
        try {key="parking:cache:v1:"+resource+":"+version(resource)+":"+suffix;}
        catch(Exception e) {failed(e);return loader.get();}
        synchronized(stripes[(key.hashCode()&Integer.MAX_VALUE)%stripes.length]) {
            if(!available()) return loader.get();
            try {
                String value=redis.opsForValue().get(key);
                if(value!=null) {metrics.counter("parking.cache.hit").increment();return json.readValue(value,type);}
            } catch(Exception e) {failed(e);return loader.get();}
            metrics.counter("parking.cache.miss").increment();
            T value=loader.get(); // Database exceptions must never be swallowed or retried as cache errors.
            try {
                long seconds=value==null?30:ttl.toSeconds();
                // Statistics never exceed their advertised 30 second freshness bound.
                long millis=(long)(seconds*1000*ThreadLocalRandom.current().nextDouble(0.9,1.0));
                redis.opsForValue().set(key,json.writeValueAsString(value),Duration.ofMillis(Math.max(1,millis)));
            } catch(Exception e) {failed(e);}
            return value;
        }
    }
    public void invalidateAfterCommit(String resource) {
        Runnable invalidate=()->{
            if(redis==null) return;
            try {redis.opsForValue().set("parking:cache:v1:revision:"+resource,UUID.randomUUID().toString());}
            catch(Exception e) {failed(e);}
        };
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){invalidate.run();}});
        } else invalidate.run();
    }
    @PreDestroy public void close() {if(factory!=null) factory.destroy();}
}
