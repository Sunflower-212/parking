package org.zjmproject;

import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.ApplicationEventPublisher;
import org.zjmproject.config.QueueConfig;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QueueConfigTest {
    @Test void smallInstanceThreadsKeepCodecAndRecoveryListener() {
        var captured=new AtomicReference<Config>();
        var client=mock(RedissonClient.class);
        try(var redisson=mockStatic(Redisson.class)) {
            redisson.when(()->Redisson.create(any(Config.class))).thenAnswer(call->{
                captured.set(call.getArgument(0));return client;
            });
            assertSame(client,new QueueConfig().redisson("redis",6379,"",2,2,mock(ApplicationEventPublisher.class)));
            Config config=captured.get();
            assertEquals(2,config.getThreads());
            assertEquals(2,config.getNettyThreads());
            assertNotNull(config.getConnectionListener());
            assertEquals(org.redisson.client.codec.StringCodec.INSTANCE,config.getCodec());
        }
    }

    @Test void invalidThreadCountsFailBeforeConnecting() {
        var config=new QueueConfig();
        var events=mock(ApplicationEventPublisher.class);
        assertThrows(IllegalArgumentException.class,()->config.redisson("redis",6379,"",0,2,events));
        assertThrows(IllegalArgumentException.class,()->config.redisson("redis",6379,"",2,-1,events));
    }
}
