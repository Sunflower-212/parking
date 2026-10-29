package org.zjmproject.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
@Service
public class MembershipService {
    private final JdbcTemplate db;private final Clock clock;
    public MembershipService(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}
    @Transactional public void change(int userId,int days,boolean renew) {
        if(days<1 || days>3650) throw new IllegalArgumentException("会员天数需在1至3650之间");
        // 先锁用户再计算新的有效期，防止两个并发续期请求都基于旧到期时间而覆盖彼此。
        var times=db.queryForObject("select vip_start_time,vip_end_time from user where id=? for update",(r,n)->new LocalDateTime[]{r.getObject(1,LocalDateTime.class),r.getObject(2,LocalDateTime.class)},userId);
        LocalDateTime now=LocalDateTime.now(clock);
        boolean active=times[1]!=null && now.isBefore(times[1]);
        if(!renew && active) throw new IllegalStateException("已经是会员");
        if(renew && times[0]==null) throw new IllegalStateException("请先开通会员");
        // 有效会员从原到期日续期，已过期会员从当前业务时间重新起算；不额外改变收费规则。
        LocalDateTime start=active?times[0]:now,end=(active?times[1]:now).plusDays(days);
        db.update("update user set status=1,vip_start_time=?,vip_end_time=?,update_time=? where id=?",start,end,now,userId);
    }
}
