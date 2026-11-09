package org.zjmproject.service;


import java.math.BigDecimal;
import org.zjmproject.pojo.Orders;
import org.zjmproject.pojo.PageBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface OrdersService {

    void addOrder(String plateNumber, Integer userId, Integer parkId, LocalDateTime enterTime, LocalDateTime exitTime, Integer parkingDuration, BigDecimal moneys);

    List<Orders> findOrdersByUserId(Integer userId);

    PageBean<Orders> adminGetPageList(Integer pageNum, Integer pageSize, String userName, String parkName, LocalDate date, Integer adminId);

    PageBean<Orders> rootGetPageList(Integer pageNum, Integer pageSize, String userName, String parkName, LocalDate date, String adminName);

    List<Orders> userGetOrdersList(Integer id, String parkName, LocalDateTime startTime, LocalDateTime endTime);

    List<Orders> findOrdersListByParkingId(Integer parkingId);

    Integer findTodayExit();

    Integer findTodayExitByParkId(Integer parkId);

    List<Orders> findExitListByDate(LocalDate localDate);

    List<Orders> findExitListByDateAndParkId(Integer parkId, LocalDate localDate);

    List<Orders> findOrdersListByDate(LocalDate localDate);
}
