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
    @Autowired private org.zjmproject.service.BackgroundTasks background;
    @Autowired private org.zjmproject.service.ParkingCapacity capacity;
    @Autowired private org.zjmproject.service.ReadCache cache;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate db;
    private void validate(Parking p) {
        if(p.getPark_name()==null || p.getPark_name().isBlank() || p.getPark_name().length()>20 || p.getPark_address()==null || p.getPark_address().isBlank() || p.getPark_address().length()>40 || p.getPark_storey()==null || p.getPark_storey()<1 || p.getPark_storey()>100 || p.getRule_id()==null || p.getAdmin_id()==null) throw new IllegalArgumentException("停车场名称、地址、楼层、规则或管理员无效");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from rule where id=?)",Boolean.class,p.getRule_id()))) throw new IllegalArgumentException("计费规则不存在，请刷新后选择");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from admin where id=?)",Boolean.class,p.getAdmin_id()))) throw new IllegalArgumentException("所属管理员不存在，请刷新后选择");
    }
    @Autowired
    private ParkingMapper parkingMapper;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void addParking(Parking parking) {
        validate(parking);
        if(parking.getPark_storey()==null || parking.getPark_storey()<1) throw new IllegalArgumentException("停车场至少需要一层");
        parking.setPark_mount(0); parking.setPark_spare(0);
        parkingMapper.addParking(parking);
        capacity.rebuild(parking.getId());
        background.audit("parking.create",List.of(parking.getId()));cache.invalidateAfterCommit("catalog");
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateParking(Parking parking) {
        validate(parking);
        db.queryForObject("select id from parking where id=? for update",Integer.class,parking.getId());
        // The controller may have established an older repeatable-read snapshot before
        // obtaining the metadata lock. Use a current read for the structural invariant.
        var top=db.queryForList("select spaces_storey from spaces where park_id=? order by spaces_storey desc limit 1 for share",Integer.class,parking.getId());
        if(!top.isEmpty() && top.get(0)>parking.getPark_storey()) throw new IllegalArgumentException("现有地图超出目标楼层，请先调整地图");
        parkingMapper.updateParking(parking);background.audit("parking.update",List.of(parking.getId()));cache.invalidateAfterCommit("catalog");cache.invalidateAfterCommit("layout:"+parking.getId());
    }

    @Override
    public PageBean<Parking> findAllParking(Integer pageNum, Integer pageSize, String parkName, String parkAddress, String adminName, String ruleName) {
        //创建pageBean对象
        PageBean<Parking> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
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
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
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
        parkingMapper.deleteParkingByParkingName(parkName);cache.invalidateAfterCommit("catalog");
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
