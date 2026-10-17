package org.zjmproject.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.ParkingMapper;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.Parking;
import org.zjmproject.service.ParkingService;

import java.util.List;

@Service
public class ParkingServiceImpl implements ParkingService {
    @Autowired
    private ParkingMapper parkingMapper;

    @Override
    public void addParking(Parking parking) {
        if(parking.getPark_storey()==null || parking.getPark_storey()<1) throw new IllegalArgumentException("停车场至少需要一层");
        parking.setPark_mount(0); parking.setPark_spare(0);
        parkingMapper.addParking(parking);
    }

    @Override
    public void updateParking(Parking parking) {
        parkingMapper.updateParking(parking);
    }

    @Override
    public PageBean<Parking> findAllParking(Integer pageNum, Integer pageSize, String parkName, String parkAddress, String adminName, String ruleName) {
        //创建pageBean对象
        PageBean<Parking> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        PageHelper.startPage(pageNum,pageSize);
        List<Parking> parkingList = parkingMapper.findAllParking(parkName,parkAddress,adminName,ruleName);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Parking> page = (Page<Parking>) parkingList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;

    }

    @Override
    public PageBean<Parking> findParkingByAdminId(Integer pageNum, Integer pageSize, String parkName, String parkAddress, String ruleName, Integer adminId) {
        //创建pageBean对象
        PageBean<Parking> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        PageHelper.startPage(pageNum,pageSize);
        List<Parking> parkingList = parkingMapper.findParking(parkName,parkAddress,ruleName,adminId);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Parking> page = (Page<Parking>) parkingList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public Parking findParkingByParkingName(String parkName) {
        return parkingMapper.findParkingByParkingName(parkName);
    }

    @Override
    public void deleteParkingByParkingName(String parkName) {
        parkingMapper.deleteParkingByParkingName(parkName);
    }

    @Override
    public Integer findParkIdByParkName(String parkName) {
        return parkingMapper.findParkIdByParkName(parkName);
    }

    @Override
    public Parking findParkByParkName(String parkName) {
        return parkingMapper.findParkByParkName(parkName);
    }

    @Override
    public Parking findParkById(Integer parkId) {return parkingMapper.findParkById(parkId);}
    @Override
    public void addParkSpare(Integer parkId) {
        parkingMapper.addParkSpare(parkId);
    }

    @Override
    public void reduceParkSpare(Integer parkId) {
        parkingMapper.reduceParkSpare(parkId);
    }

    @Override
    public String getAdminNameByParkName(String parkName) {
        return parkingMapper.getAdminNameByParkName(parkName);
    }

    @Override
    public List<Parking> parkingList() {
        return parkingMapper.parkingList();
    }

    @Override
    public List<Parking> adminGetParkingList(Integer adminId) {
        return parkingMapper.adminGetParkingList(adminId);
    }

    @Override
    public List<Parking> findParkByRuleId(Integer ruleId) {
        return parkingMapper.findParkByRuleId(ruleId);
    }

    @Override
    public Parking findParkingById(Integer id) {
        return parkingMapper.findParkingById(id);
    }


}
