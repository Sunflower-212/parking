package org.zjmproject.service.impl;

import java.math.BigDecimal;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.RechargesMapper;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.Recharges;
import org.zjmproject.pojo.Spaces;
import org.zjmproject.service.RechargesService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class RechargesServiceImpl implements RechargesService {
    @Autowired private java.time.Clock clock;
    @Autowired
    private RechargesMapper rechargesMapper;

    @Override
    public void add(Recharges recharges) {
        recharges.setCreate_time(LocalDateTime.now(clock));
        rechargesMapper.add(recharges);
    }

    @Override
    public List<Recharges> findRechargesByUserId(Integer userId, BigDecimal amounts, LocalDateTime startTime, LocalDateTime endTime) {
        return rechargesMapper.findRechargesByUserId(userId,amounts,startTime,endTime);
    }

    @Override
    public PageBean<Recharges> findRechargesByDate(Integer pageNum, Integer pageSize,BigDecimal amounts, LocalDate date) {
        //创建pageBean对象
        PageBean<Recharges> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        LocalDateTime startDateTime=null;
        LocalDateTime endDateTime=null;
        if(date!=null){
            startDateTime = LocalDateTime.of(date, LocalTime.of(0,0,0));
            endDateTime = date.plusDays(1).atStartOfDay();
        }
        List<Recharges> rechargesList = rechargesMapper.findRechargesByDate(amounts,startDateTime,endDateTime);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Recharges> page = (Page<Recharges>) rechargesList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public Recharges findRechargeById(Integer rechargeId) {
        return rechargesMapper.findRechargesById(rechargeId);
    }

    @Override
    public void updateRecharges(Recharges recharges) {
        rechargesMapper.updateRecharges(recharges);
    }

    @Override
    public void checkAndUpdateRechargesFlag() {
        while(rechargesMapper.expireUnpaid(LocalDateTime.now(clock).minusMinutes(5))==500) { /* conditional batches preserve payment race safety */ }
    }

    @Override
    public List<Recharges> findRechargesListByDate(LocalDate localDate) {
        LocalDateTime startDateTime = localDate.atStartOfDay();
        LocalDateTime endDateTime = localDate.plusDays(1).atStartOfDay();
        return rechargesMapper.findRechargesListByDate(startDateTime,endDateTime);
    }

}
