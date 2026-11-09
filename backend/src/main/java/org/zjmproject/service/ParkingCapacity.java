package org.zjmproject.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/** Each space maps to exactly one durable bucket. Readers sum buckets in MySQL. */
// 数据库容量计数器：不同车位分散更新固定桶，读取通过汇总视图取得总余位。
@Service
public class ParkingCapacity {
    private final JdbcTemplate db;private final MeterRegistry metrics;private final int buckets;
    public ParkingCapacity(JdbcTemplate db,MeterRegistry metrics,@Value("${parking.capacity-buckets:16}") int buckets) {
        if(buckets!=1 && buckets!=16)throw new IllegalArgumentException("Capacity buckets must be 1 or 16");
        this.db=db;this.metrics=metrics;this.buckets=buckets;
    }
    // 启动时核对迁移使用的桶数，阻止新旧映射同时写入导致余位失真。
    @jakarta.annotation.PostConstruct
    public void verifySchema() {
        if(db.queryForObject("select buckets from capacity_settings where id=1",Integer.class)!=buckets)
            throw new IllegalStateException("Capacity migration and CAPACITY_BUCKETS do not match; stop all writers before migrating");
    }
    // 调用者必须处于车辆事务中，并在其他业务 SQL 完成后更新容量，缩短热点行锁的持有窗口。
    public void adjust(Object parkId,int spaceId,int change) {
        long start=System.nanoTime();
        try {
            // 同一条条件更新防止桶内余位越界；失败抛异常，让占位、订单和余额一起回滚。
            if(db.update("update parking_capacity set park_spare=park_spare+? where park_id=? and bucket_id=? and park_spare+? between 0 and park_mount",change,parkId,bucket(spaceId),change)!=1)
                throw new IllegalStateException("余位数据异常，请核对停车场容量");
        } finally {metrics.timer("parking.flow.capacity.update").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    public int bucket(int spaceId) {
        if(buckets==1)return 0;
        // 与迁移 SQL 的 CRC32(CAST(id AS CHAR)) 保持一致；不能换成 Java hashCode 或按运行时随机分桶。
        var crc=new java.util.zip.CRC32();crc.update(Integer.toString(spaceId).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return (int)(crc.getValue()%buckets);
    }
    // 过期预约转为现场入场时，总余位不变；跨桶换位仍需分别归还旧桶、扣减新桶。
    public void transfer(int parkId,int fromSpace,int toSpace) {
        int from=bucket(fromSpace),to=bucket(toSpace);
        if(from==to)return;
        // Multiple capacity rows are always locked in ascending bucket order.
        if(from<to){adjust(parkId,fromSpace,1);adjust(parkId,toSpace,-1);}
        else{adjust(parkId,toSpace,-1);adjust(parkId,fromSpace,1);}
    }
    /** Caller holds parking metadata exclusively, excluding all vehicle transitions. */
    public void rebuild(int parkId) {
        // 仅地图结构变更时全量重建；由调用者持有停车场资料排他锁，排除并发车辆操作。
        String numbers=IntStream.range(0,buckets).mapToObj(n->"select "+n+" n").collect(Collectors.joining(" union all "));
        db.update("insert into parking_capacity(park_id,bucket_id,park_mount,park_spare) select ?,b.n,coalesce(sum(s.spaces_flag in (0,1)),0),coalesce(sum(s.spaces_flag=0),0) from ("+numbers+") b left join spaces s on s.park_id=? and mod(crc32(cast(s.id as char)),?)=b.n group by b.n on duplicate key update park_mount=values(park_mount),park_spare=values(park_spare)",parkId,parkId,buckets);
    }
}
