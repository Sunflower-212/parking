package org.zjmproject.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

public final class MoneysUtil {
    private MoneysUtil() {}
    public static BigDecimal getMoneys(Integer duration,Integer free,Integer unit,BigDecimal cost) {
        if(duration<0 || free<0 || unit<=0 || cost.signum()<0) throw new IllegalArgumentException("计费规则或停车时间无效");
        if(duration<=free) return BigDecimal.ZERO.setScale(2);
        long units=((long)duration-free+unit-1)/unit;
        return cost.multiply(BigDecimal.valueOf(units)).setScale(2,RoundingMode.HALF_UP);
    }
    public static Integer getDuration(LocalDateTime start,LocalDateTime end) {
        if(start==null || end==null || end.isBefore(start)) throw new IllegalArgumentException("停车时间无效");
        return Math.toIntExact(Duration.between(start,end).toMinutes());
    }
}
