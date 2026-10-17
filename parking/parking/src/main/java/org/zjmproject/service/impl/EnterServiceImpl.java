package org.zjmproject.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.EnterMapper;
import org.zjmproject.mapper.SpacesMapper;
import org.zjmproject.mapper.UserMapper;
import org.zjmproject.pojo.Enter;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.User;
import org.zjmproject.service.EnterService;
import org.zjmproject.service.UserService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class EnterServiceImpl implements EnterService {
    @Autowired private java.time.Clock clock;
    @Autowired
    private EnterMapper enterMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SpacesMapper spacesMapper;
    @Override
    public void add(String plateNumber, Integer userId, Integer parkId) {
        enterMapper.add(plateNumber,userId,parkId);
    }

    @Override
    public Enter isVehicleInParkingLot(String plateNumber,Integer flag) {
        return enterMapper.isVehicleInParkingLot(plateNumber,flag);
    }

    @Override
    public void updateFlag(Enter enter) {
        enterMapper.updateFlag(enter);
    }

    @Override
    public PageBean<Enter> userGetPageList(Integer pageNum, Integer pageSize, String parkName, Integer id, LocalDate date) {
        //创建pageBean对象
        PageBean<Enter> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        LocalDateTime startDateTime=null;
        LocalDateTime endDateTime=null;
        if(date!=null){
            startDateTime = LocalDateTime.of(date, LocalTime.of(0,0,0));
            endDateTime = date.plusDays(1).atStartOfDay();
        }
        List<Enter> enterList = enterMapper.userGetPageList(parkName,id,startDateTime,endDateTime);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Enter> page = (Page<Enter>) enterList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public PageBean<Enter> adminGetPageList(Integer pageNum, Integer pageSize, Integer id,String plateNum, String parkName, Integer flag) {
        //创建pageBean对象
        PageBean<Enter> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        List<Enter> enterList = enterMapper.adminGetPageList(id,plateNum,parkName,flag);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Enter> page = (Page<Enter>) enterList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public PageBean<Enter> rootGetPageList(Integer pageNum, Integer pageSize, String plateNum, String parkName, Integer flag, String adminName) {
        //创建pageBean对象
        PageBean<Enter> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        List<Enter> enterList = enterMapper.rootGetPageList(plateNum,parkName,flag,adminName);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Enter> page = (Page<Enter>) enterList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public List<Enter> findEnterListByParkingId(Integer parkingId) {
        return enterMapper.findEnterListByParkingId(parkingId);
    }

    @Override
    public Enter isReservationParkingLot(String plateNumber, Integer flag, Integer reservation) {
        return enterMapper.isReservationParkingLot(plateNumber,flag,reservation);
    }

    @Override
    public void update(Enter enterRes) {
        enterMapper.update(enterRes);
    }

    @Override
    public List<Enter> isVehicle(Integer userId, Integer flag) {
        return enterMapper.isVehicle(userId,flag);
    }

    @Override
    public List<Enter> isReservation(Integer userId, Integer flag, Integer reservation) {
        return enterMapper.isReservation(userId,flag,reservation);
    }

    @Override
    public Enter findEnterById(Integer id) {
        return enterMapper.findEnterById(id);
    }

    @Override
    public void addEnter(Enter enter) {
        enterMapper.addEnter(enter);
    }

    @Override
    public Integer findTodayEnter() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime startDateTime = today.atStartOfDay();
        LocalDateTime endDateTime = today.plusDays(1).atStartOfDay();
        return enterMapper.countToday(startDateTime,endDateTime);
    }

    @Override
    public Integer findTodayEnterByParkId(Integer parkId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime startDateTime = today.atStartOfDay();
        LocalDateTime endDateTime = today.plusDays(1).atStartOfDay();
        return enterMapper.countTodayPark(parkId,startDateTime,endDateTime);
    }

    @Override
    public List<Enter> findEnterListByDate(LocalDate localDate) {
        LocalDateTime startDateTime = localDate.atStartOfDay();
        LocalDateTime endDateTime = localDate.plusDays(1).atStartOfDay();
        return enterMapper.findTodayEnter(startDateTime,endDateTime);
    }

    @Override
    public List<Enter> findEnterListByDateAndParkId(Integer parkId, LocalDate localDate) {
        LocalDateTime startDateTime = localDate.atStartOfDay();
        LocalDateTime endDateTime = localDate.plusDays(1).atStartOfDay();
        return enterMapper.findTodayEnterByParkId(parkId,startDateTime,endDateTime);
    }

}
