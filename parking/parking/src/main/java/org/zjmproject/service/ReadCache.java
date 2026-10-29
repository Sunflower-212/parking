package org.zjmproject.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Rebuildable metadata cache; never used for authorization, balances or live occupancy. */
// 独立缓存 Redis 只保存可重建的读取结果；状态 Redis 中的 Token 和预约队列不受这里的淘汰影响。
@Service
public class ReadCache {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReadCache.class);
    private static final String EPOCH="parking:cache:v2:epoch";
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    // 固定数量的回源互斥对象，控制锁对象内存；不同键可能共用一把锁，但命中请求不经过它。
    private final Object[] stripes=new Object[64];
    private final Object epochLock=new Object();
    private final AtomicLong generation=new AtomicLong();
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate redis;
    private final Cache<String,String> local;
    private final long localTtlMillis;
    private volatile long unavailableUntil;
    private volatile boolean dirty;

    @Autowired
    public ReadCache(ObjectMapper json,MeterRegistry metrics,
            @Value("${parking.cache.enabled:false}") boolean enabled,
            @Value("${parking.cache.host:localhost}") String host,
            @Value("${parking.cache.port:6379}") int port,
            @Value("${parking.cache.password:}") String password,
            @Value("${parking.cache.local-enabled:false}") boolean localEnabled,
            @Value("${parking.cache.local-ttl:1s}") Duration localTtl,
            @Value("${parking.cache.local-weight-bytes:16777216}") long localWeight) {
        this.json=json;this.metrics=metrics;
        if(localTtl.isNegative() || localTtl.isZero() || localTtl.compareTo(Duration.ofSeconds(1))>0 || localWeight<1024) throw new IllegalArgumentException("Local cache budget or TTL is invalid");
        localTtlMillis=localTtl.toMillis();
        // 本地层仅短暂保存公共资料与布局，并限制估算权重；不缓存余额、权限或实时占用。
        local=enabled && localEnabled?Caffeine.newBuilder().maximumWeight(localWeight)
                .weigher((String key,String value)->key.length()*2+value.length()*2+128)
                .expireAfterWrite(localTtl).build():null;
        for(int i=0;i<stripes.length;i++) stripes[i]=new Object();
        if(enabled) {
            var config=new RedisStandaloneConfiguration(host,port);
            if(!password.isBlank()) config.setPassword(password);
            var options=ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build()).build();
            // 缓存连接使用短超时；失败后回源并退避，避免缓存故障拖住主业务线程。
            var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).clientOptions(options).build();
            factory=new LettuceConnectionFactory(config,client);factory.afterPropertiesSet();factory.start();
            redis=new StringRedisTemplate(factory);
        } else {factory=null;redis=null;}
        metrics.gauge("parking.cache.invalidation.pending",this,value->value.dirty?1:0);
    }
    public ReadCache(ObjectMapper json,MeterRegistry metrics,boolean enabled,String host,int port,String password) {
        this(json,metrics,enabled,host,port,password,false,Duration.ofSeconds(1),16777216);
    }
    private boolean available() {return redis!=null && System.currentTimeMillis()>=unavailableUntil;}
    private void clearLocal() {generation.incrementAndGet();if(local!=null)local.invalidateAll();}
    private void failed(Exception e) {
        // 失效或缓存访问失败时清空本地层并标脏，恢复后先更换命名空间，再允许缓存命中。
        synchronized(epochLock) {dirty=true;unavailableUntil=System.currentTimeMillis()+10000;clearLocal();}
        metrics.counter("parking.cache.failure").increment();
        log.atWarn().addKeyValue("event","cache.fallback").addKeyValue("result","DEGRADED")
            .addKeyValue("reasonCode",e.getClass().getSimpleName()).log("database_fallback_10s");
    }
    private <T> T timedRedis(String operation,Supplier<T> call) {
        long start=System.nanoTime();try{return call.get();}
        finally {metrics.timer("parking.cache.redis.duration","operation",operation).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    private String epoch() {
        // Failed invalidation disables all read-cache hits until a new namespace is installed.
        // A single dirty bit avoids an unbounded collection of pending resource invalidations.
        if(dirty) synchronized(epochLock) {
            if(dirty) {timedRedis("invalidate",()->{redis.opsForValue().set(EPOCH,UUID.randomUUID().toString());return true;});clearLocal();dirty=false;}
        }
        String current=timedRedis("version",()->redis.opsForValue().get(EPOCH));
        if(current!=null)return current;
        timedRedis("version-create",()->redis.opsForValue().setIfAbsent(EPOCH,UUID.randomUUID().toString()));
        current=timedRedis("version",()->redis.opsForValue().get(EPOCH));
        if(current==null)throw new IllegalStateException("Cache revision unavailable");
        return current;
    }
    private boolean eligible(String resource) {return local!=null && (resource.equals("catalog") || resource.equals("rules") || resource.startsWith("layout:"));}
    private <T> T decode(String value,TypeReference<T> type) throws java.io.IOException {
        long start=System.nanoTime();try{return json.readValue(value,type);}
        finally {metrics.timer("parking.cache.codec.duration","operation","decode").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    private String encode(Object value) throws java.io.IOException {
        long start=System.nanoTime();try{return json.writeValueAsString(value);}
        finally {metrics.timer("parking.cache.codec.duration","operation","encode").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    // 本地代数必须匹配，防止资料修改之前发起的慢请求把旧结果重新填入当前本地缓存。
    private void remember(String key,String value,long expectedGeneration,boolean eligible) {
        if(eligible && !dirty && expectedGeneration==generation.get() && value.length()<=524288)local.put(key,value);
    }
    private <T> T load(Supplier<T> loader) {
        long start=System.nanoTime();try{return loader.get();}
        finally {metrics.timer("parking.cache.load.duration").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    private <T> T loadGuarded(Supplier<T> loader) {
        try{return load(loader);}catch(RuntimeException e){throw new LoaderFailure(e);}
    }
    public <T> T get(String resource,String suffix,Duration ttl,TypeReference<T> type,Supplier<T> loader) {
        if(!available())return load(loader);
        long currentGeneration=generation.get();
        boolean useLocal=eligible(resource);
        String localKey=currentGeneration+":"+resource+":"+suffix;
        try {
            if(useLocal && !dirty) {
                String value=local.getIfPresent(localKey);
                if(value!=null) {metrics.counter("parking.cache.local.hit").increment();metrics.counter("parking.cache.hit").increment();return decode(value,type);}
            }
            // 版本进入缓存键：并发回源即使迟到写入，也只落到旧命名空间，后续请求不再访问。
            String key="parking:cache:v2:"+epoch()+":"+resource+":"+suffix;
            String hit=timedRedis("get",()->redis.opsForValue().get(key));
            if(hit!=null) {
                metrics.counter("parking.cache.redis.hit").increment();metrics.counter("parking.cache.hit").increment();
                remember(localKey,hit,currentGeneration,useLocal);return decode(hit,type);
            }
            // Hits do not acquire the single-flight lock. Only competing cache misses wait.
            long waitStart=System.nanoTime();
            synchronized(stripes[(key.hashCode()&Integer.MAX_VALUE)%stripes.length]) {
                metrics.timer("parking.cache.singleflight.wait").record(System.nanoTime()-waitStart,TimeUnit.NANOSECONDS);
                if(!available() || dirty)return loadGuarded(loader);
                // 取得回源锁后再次检查缓存，复用先到请求的结果，避免同一热点同时查库。
                hit=timedRedis("get",()->redis.opsForValue().get(key));
                if(hit!=null) {
                    metrics.counter("parking.cache.redis.hit").increment();metrics.counter("parking.cache.hit").increment();
                    remember(localKey,hit,currentGeneration,useLocal);return decode(hit,type);
                }
                metrics.counter("parking.cache.miss").increment();
                T value=loadGuarded(loader); // Database errors must not be caught as cache failures.
                try {
                    String encoded=encode(value);
                    // 空资料短暂缓存以减少穿透；TTL 加随机偏移，并预留本地层时长，避免两层叠加超出预算。
                    long seconds=value==null?30:ttl.toSeconds();
                    long budget=seconds*1000-(useLocal?localTtlMillis:0);
                    long millis=Math.max(1,(long)(budget*ThreadLocalRandom.current().nextDouble(0.9,1.0)));
                    timedRedis("set",()->{redis.opsForValue().set(key,encoded,Duration.ofMillis(millis));return true;});
                    remember(localKey,encoded,currentGeneration,useLocal);
                }catch(Exception e){failed(e);}
                return value;
            }
        }catch(org.springframework.dao.DataAccessException | java.io.IOException | IllegalStateException e) {
            // Database loader exceptions have to propagate without a duplicate database read.
            if(e instanceof LoaderFailure failure)throw failure.original;
            failed(e);return load(loader);
        }
    }
    private static class LoaderFailure extends IllegalStateException {
        final RuntimeException original;
        LoaderFailure(RuntimeException original){this.original=original;}
    }
    public void invalidateAfterCommit(String resource) {
        Runnable invalidate=()->{
            synchronized(epochLock) {
                clearLocal();dirty=true;
                if(redis==null){dirty=false;return;}
                try {timedRedis("invalidate",()->{redis.opsForValue().set(EPOCH,UUID.randomUUID().toString());return true;});dirty=false;}
                catch(Exception e){failed(e);}
            }
        };
        // 事务提交成功后才切换缓存版本；回滚不失效，避免其他请求在提交前重新缓存旧数据。
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){invalidate.run();}});
        else invalidate.run();
    }
    @PreDestroy public void close() {if(local!=null)local.invalidateAll();if(factory!=null)factory.destroy();}
}
