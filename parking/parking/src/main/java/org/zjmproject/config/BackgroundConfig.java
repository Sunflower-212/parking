package org.zjmproject.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zjmproject.service.BackgroundTasks;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

@Configuration
@ConditionalOnProperty(name = "parking.background.enabled", havingValue = "true")
public class BackgroundConfig {
    public static final String EXCHANGE = "parking.background", QUEUE = "parking.background.work", DEAD = "parking.background.dead";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BackgroundConfig.class);
    private final BackgroundTasks tasks;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "background-outbox");
        t.setDaemon(true);
        return t;
    });

    public BackgroundConfig(BackgroundTasks tasks, RabbitTemplate rabbit, MeterRegistry metrics) {
        this.tasks = tasks;
        this.rabbit = rabbit;
        this.metrics = metrics;
    }

    @Bean
    Declarables backgroundTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("dead").build();
        var dead = QueueBuilder.durable(DEAD).build();
        return new Declarables(exchange, queue, dead, BindingBuilder.bind(queue).to(exchange).with("work"), BindingBuilder.bind(dead).to(exchange).with("dead"));
    }

    @PostConstruct
    void start() {
        // 启用不可路由返回检查；仅 Broker Confirm 成功仍不足以证明消息已路由到目标队列。
        rabbit.setMandatory(true);
        worker.scheduleWithFixedDelay(this::dispatch, 2, 1, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(() -> {
            try {
                tasks.cleanup();
            } catch (Exception e) {
                log.warn("Export cleanup failed ({})", e.getClass().getSimpleName());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public void dispatch() {
        try {
            // 每轮限制领取数量；领取租约与网络投递分开，不在持有数据库事务时等待 Broker。
            for (int i = 0; i < 20; i++) {
                var event = tasks.claimDelivery();
                if (event == null) return;
                try (var context=org.zjmproject.observability.RuntimeEvents.scope((String)event.get("request_id"))) {
                    String id = event.get("id").toString();
                    var properties = new MessageProperties();
                    properties.setMessageId(id);
                    properties.setContentType("text/plain");
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    var correlation = new CorrelationData(event.get("lease_token").toString());
                    rabbit.send(EXCHANGE, Boolean.TRUE.equals(event.get("dead")) ? "dead" : "work", new Message(id.getBytes(StandardCharsets.UTF_8), properties), correlation);
                    // 同时校验 Confirm 与 Return，满足两者才将持久化事件标为已投递；确认丢失允许后续重复投递。
                    var confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                    if (!confirm.isAck() || correlation.getReturned() != null)
                        throw new IllegalStateException("Broker did not route and confirm event");
                    tasks.delivered(event);
                    org.zjmproject.observability.RuntimeEvents.event("background.publish",Boolean.TRUE.equals(event.get("dead"))?"DEAD":"SUCCESS",id);
                    metrics.counter("parking.background.published").increment();
                } catch (Exception e) {
                    tasks.deliveryFailed(event);
                    metrics.counter("parking.background.publish.failure").increment();
                    try(var context=org.zjmproject.observability.RuntimeEvents.scope((String)event.get("request_id"))){
                        log.atWarn().addKeyValue("event","background.publish").addKeyValue("result","RETRY")
                            .addKeyValue("entityId",event.get("id")).addKeyValue("reasonCode",e.getClass().getSimpleName()).log("delivery_postponed");
                    }
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Outbox dispatch unavailable ({})", e.getClass().getSimpleName());
        }
    }

    @RabbitListener(queues = QUEUE, ackMode = "MANUAL", concurrency = "1")
    public void receive(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        String id = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!id.matches("[a-f0-9-]{36}")) {
            channel.basicReject(tag, false);
            return;
        }
        try {
            // 业务完成或已可靠记录下一次重试后才手动 ACK；异常交回 Broker，数据库幂等处理重复消息。
            tasks.consume(id);
            channel.basicAck(tag, false);
            metrics.counter("parking.background.acknowledged").increment();
        } catch (Exception failure) {
            metrics.counter("parking.background.consume.failure").increment();
            // Do not leave a MANUAL delivery occupying the only prefetch slot until restart.
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            // 基础设施故障重新入队，释放当前手动确认槽位，避免唯一消费者一直被未确认消息占住。
            channel.basicNack(tag, false, true);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
