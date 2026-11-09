package org.zjmproject.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.connection.ConnectionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class QueueConfig {
    public record RedisConnected() {}
    @Bean public Clock clock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }
    @Bean(destroyMethod="shutdown")
    public RedissonClient redisson(@Value("${spring.data.redis.host:localhost}") String host,
                                   @Value("${spring.data.redis.port:6379}") int port,
                                   @Value("${spring.data.redis.password:}") String password,
                                   @Value("${parking.queue.worker-threads:16}") int workerThreads,
                                   @Value("${parking.queue.netty-threads:32}") int nettyThreads,
                                   ApplicationEventPublisher events) {
        if(workerThreads<1 || nettyThreads<1) throw new IllegalArgumentException("Queue thread counts must be positive");
        Config config=new Config();
        // 小内存实例显式限制客户端线程；默认值沿用 Redisson，队列与重连恢复规则不变。
        config.setThreads(workerThreads).setNettyThreads(nettyThreads);
        config.setCodec(org.redisson.client.codec.StringCodec.INSTANCE);
        config.useSingleServer().setAddress("redis://"+host+":"+port).setPassword(password.isBlank()?null:password)
                .setConnectionMinimumIdleSize(2).setConnectionPoolSize(8).setSubscriptionConnectionMinimumIdleSize(1);
        config.setConnectionListener(new ConnectionListener() {
            private final java.util.concurrent.atomic.AtomicBoolean disconnected=new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void onConnect(InetSocketAddress addr) { if(disconnected.getAndSet(false)) events.publishEvent(new RedisConnected()); }
            @Override public void onDisconnect(InetSocketAddress addr) { disconnected.set(true); }
        });
        return Redisson.create(config);
    }
}
