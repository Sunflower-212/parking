package org.zjmproject.service;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.Recharges;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface RechargesService {
    @Insert("insert into recharges(user_id, amounts, create_time, pay_method)" +
            " values(#{user_id},#{amounts},#{create_time},#{pay_method})")
    void add(Recharges recharges);

    List<Recharges> findRechargesByUserId(Integer userId, BigDecimal amounts, LocalDateTime startTime, LocalDateTime endTime);

    PageBean<Recharges> findRechargesByDate(Integer pageNum, Integer pageSize,BigDecimal amounts, LocalDate date);


    Recharges findRechargeById(Integer outTradeNO);

    void updateRecharges(Recharges recharges);

    void checkAndUpdateRechargesFlag();

    List<Recharges> findRechargesListByDate(LocalDate localDate);
}
