package org.zjmproject.service;

import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.Parking;

import java.util.List;

public interface ParkingService {
    void addParking(Parking parking);

    void updateParking(Parking parking);

    PageBean<Parking> findAllParking(Integer pageNum, Integer pageSize, String parkName, String parkAddress, String adminName, String ruleName);


    PageBean<Parking> findParkingByAdminId(Integer pageNum, Integer pageSize, String parkName, String parkAddress, String ruleName, Integer adminId);

    Parking findParkingByParkingName(String parkName);

    void deleteParkingByParkingName(String parkName);

    Integer findParkIdByParkName(String parkName);

    Parking findParkByParkName(String parkName);



    String getAdminNameByParkName(String parkName);

    List<Parking> parkingList();

    List<Parking> adminGetParkingList(Integer adminId);

    List<Parking> findParkByRuleId(Integer id);

    Parking findParkingById(Integer id);

    Parking findParkById(Integer parkId);
}
