package org.zjmproject.mapper;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.*;
import org.zjmproject.pojo.Recharges;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RechargesMapper {
    @Update("update recharges set flag=2 where id=#{id} and flag=0")
    int cancelUnpaid(Integer id);

    void add(Recharges recharges);
    List<Recharges> findRechargesByUserId(Integer userId, BigDecimal amounts, LocalDateTime startTime, LocalDateTime endTime);

    List<Recharges> findRechargesByDate(BigDecimal amounts, LocalDateTime startDateTime, LocalDateTime endDateTime);

    @Select("select * from recharges where id=#{rechargesId}")
    Recharges findRechargesById(Integer rechargesId);

    @Update("update recharges set user_id=#{user_id},amounts=#{amounts},pay_no=#{pay_no},flag=#{flag},pay_method=#{pay_method},pay_time=#{pay_time} where id=#{id}")
    void updateRecharges(Recharges recharges);

    @Select("select * from recharges where flag=#{flag}")
    List<Recharges> findRechargesByFlag(Integer flag);

    @Select("select * from recharges where create_time>=#{startDateTime} and create_time<#{endDateTime} and flag=1")
    List<Recharges> findRechargesListByDate(LocalDateTime startDateTime, LocalDateTime endDateTime);
}
