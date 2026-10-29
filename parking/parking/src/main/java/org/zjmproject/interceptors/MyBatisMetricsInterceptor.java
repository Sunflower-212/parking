package org.zjmproject.interceptors;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Intercepts({@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
public class MyBatisMetricsInterceptor implements Interceptor {
    private final MeterRegistry metrics;

    public MyBatisMetricsInterceptor(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var statement = (MappedStatement) invocation.getArgs()[0];
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            // 指标标签只使用固定 Mapper 标识与操作名，避免把 SQL 参数、用户 ID 等敏感或高基数字段放入指标。
            metrics.timer("parking.mybatis.duration", "statement", statement.getId(), "operation", invocation.getMethod().getName()).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
