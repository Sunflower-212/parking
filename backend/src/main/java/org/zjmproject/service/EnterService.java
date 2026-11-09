package org.zjmproject.service;

import org.zjmproject.pojo.Enter;
import org.zjmproject.pojo.PageBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface EnterService {

    void add(String plateNumber, Integer userId, Integer parkId);

    Enter isVehicleInParkingLot(String plateNumber,Integer flag);

    void updateFlag(Enter enter);

    PageBean<Enter> userGetPageList(Integer pageNum, Integer pageSize, String parkName, Integer id, LocalDate date);

    PageBean<Enter> adminGetPageList(Integer pageNum, Integer pageSize,Integer id, String plateNum, String parkName, Integer flag);

    PageBean<Enter> rootGetPageList(Integer pageNum, Integer pageSize, String plateNum, String parkName, Integer flag, String adminName);

    List<Enter> findEnterListByParkingId(Integer parkingId);

    Enter isReservationParkingLot(String plateNumber, Integer flag, Integer reservation);

    void update(Enter enterRes);

    List<Enter> isVehicle(Integer userId, Integer flag);

    List<Enter> isReservation(Integer userId, Integer flag, Integer reservation);

    Enter findEnterById(Integer id);

    void addEnter(Enter enter);

    Integer findTodayEnter();

    Integer findTodayEnterByParkId(Integer parkId);

    List<Enter> findEnterListByDate(LocalDate localDate);

    List<Enter> findEnterListByDateAndParkId(Integer parkId, LocalDate localDate);


}
