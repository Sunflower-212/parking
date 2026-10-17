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
@Intercepts({@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
        @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})})
public class MyBatisMetricsInterceptor implements Interceptor {
    private final MeterRegistry metrics;
    public MyBatisMetricsInterceptor(MeterRegistry metrics){this.metrics=metrics;}
    @Override public Object intercept(Invocation invocation) throws Throwable {
        var statement=(MappedStatement)invocation.getArgs()[0];long start=System.nanoTime();
        try{return invocation.proceed();}
        finally{metrics.timer("parking.mybatis.duration","statement",statement.getId(),"operation",invocation.getMethod().getName()).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
}
