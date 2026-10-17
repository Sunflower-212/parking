package org.zjmproject.service.impl;

import java.math.BigDecimal;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.OrdersMapper;
import org.zjmproject.pojo.Orders;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.service.OrdersService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class OrdersServiceImpl implements OrdersService {
    @Autowired
    private OrdersMapper ordersMapper;

    @Override
    public void addOrder(String plateNumber, Integer userId, Integer parkId, LocalDateTime enterTime, LocalDateTime exitTime, Integer parkingDuration, BigDecimal moneys) {
        ordersMapper.addOrder(plateNumber,userId,parkId,enterTime,exitTime,parkingDuration,moneys);
    }

    @Override
    public List<Orders> findOrdersByUserId(Integer userId) {
        return ordersMapper.findOrdersByUserId(userId);
    }

    @Override
    public PageBean<Orders> adminGetPageList(Integer pageNum, Integer pageSize, String userName, String parkName, LocalDate date, Integer adminId) {
        //创建pageBean对象
        PageBean<Orders> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        PageHelper.startPage(pageNum,pageSize);
        LocalDateTime startDateTime=null;
        LocalDateTime endDateTime=null;
        if(date!=null){
            startDateTime = LocalDateTime.of(date, LocalTime.of(0,0,0));
            endDateTime = LocalDateTime.of(date,LocalTime.MAX);
        }
        List<Orders> ordersList = ordersMapper.adminGetPageList(userName,parkName,startDateTime,endDateTime,adminId);
        Page<Orders> page = (Page<Orders>) ordersList;
        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public PageBean<Orders> rootGetPageList(Integer pageNum, Integer pageSize, String userName, String parkName, LocalDate date, String adminName) {
        //创建pageBean对象
        PageBean<Orders> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        PageHelper.startPage(pageNum,pageSize);
        LocalDateTime startDateTime=null;
        LocalDateTime endDateTime=null;
        if(date!=null){
            startDateTime = LocalDateTime.of(date, LocalTime.of(0,0,0));
            endDateTime = LocalDateTime.of(date,LocalTime.MAX);
        }
        List<Orders> ordersList = ordersMapper.rootGetPageList(userName,parkName,startDateTime,endDateTime,adminName);
        Page<Orders> page = (Page<Orders>) ordersList;
        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public List<Orders> userGetOrdersList(Integer userId, String parkName, LocalDateTime startTime, LocalDateTime endTime) {
        return ordersMapper.userGetOrdersList(userId,parkName,startTime,endTime);
    }

    @Override
    public List<Orders> findOrdersListByParkingId(Integer parkingId) {
        return ordersMapper.findOrdersListByParkingId(parkingId);
    }

    @Override
    public Integer findTodayExit() {
        LocalDate today = LocalDate.now();
        LocalDateTime startDateTime = today.atStartOfDay();
        LocalDateTime endDateTime = today.atTime(LocalTime.MAX);
        return ordersMapper.findTodayExit(startDateTime,endDateTime).size();
    }

    @Override
    public Integer findTodayExitByParkId(Integer parkId) {
        LocalDate today = LocalDate.now();
        LocalDateTime startDateTime = today.atStartOfDay();
        LocalDateTime endDateTime = today.atTime(LocalTime.MAX);
        return ordersMapper.findTodayExitByParkId(parkId,startDateTime,endDateTime).size();
    }

    @Override
    public List<Orders> findExitListByDate(LocalDate localDate) {
        LocalDateTime startDateTime = localDate.atStartOfDay();
        LocalDateTime endDateTime = localDate.atTime(LocalTime.MAX);
        return ordersMapper.findTodayExit(startDateTime,endDateTime);
    }

    @Override
    public List<Orders> findExitListByDateAndParkId(Integer parkId, LocalDate localDate) {
        LocalDateTime startDateTime = localDate.atStartOfDay();
        LocalDateTime endDateTime = localDate.atTime(LocalTime.MAX);
        return ordersMapper.findTodayExitByParkId(parkId,startDateTime,endDateTime);
    }

    @Override
    public List<Orders> findOrdersListByDate(LocalDate localDate) {
        LocalDateTime startDateTime = localDate.atStartOfDay();
        LocalDateTime endDateTime = localDate.atTime(LocalTime.MAX);
        return ordersMapper.findTodayExit(startDateTime,endDateTime);
    }
}
