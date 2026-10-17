package org.zjmproject.service.impl;


import java.math.BigDecimal;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.UserMapper;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.User;
import org.zjmproject.service.UserService;
import org.zjmproject.utils.Md5Util;
import org.zjmproject.utils.ThreadLocalUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static java.time.LocalDateTime.now;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public User findByUserName(String username) {

        User user = userMapper.findByUserName(username);
        return user;
    }

    @Override
    public void register(String username, String password) {
        //加密
        String md5String = Md5Util.encode(password);
        //添加
        userMapper.add(username,md5String);
    }

    @Override
    public void update(User user) {
        Map<String,Object> actor = ThreadLocalUtil.get();
        user.setId((Integer) actor.get("id"));
        user.setUpdate_time(now());
        userMapper.update(user);
    }

    @Override
    public void updateAvatar(String avatarUrl) {
        Map<String,Object> map= ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        userMapper.updateAvatar(avatarUrl,id);
    }

    @Override
    public void updatePassword(String newPwd,Integer userId) {
        userMapper.updatePassword(Md5Util.encode(newPwd),userId);
    }

    @Override
    public void deleteUser(String username) {
        userMapper.deleteUser(username);
    }

    @Override
    public PageBean<User> pageListFind(Integer pageNum, Integer pageSize, String plateNum, String carColor, Integer status,String userName) {
        //创建pageBean对象
        PageBean<User> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        PageHelper.startPage(pageNum,pageSize);
        List<User> userList = userMapper.pageListFind(plateNum,carColor,status,userName);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<User> page = (Page<User>) userList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;

    }

    @Override
    public void getVip(Integer id,Integer days) {
        userMapper.updateVipData(id,now(),now().plusDays(days));
    }

    @Override
    public LocalDateTime getVipEndTime(Integer id) {
        return userMapper.getVipEndTime(id);
    }

    @Override
    public void vipRenewal(Integer id, Integer days, LocalDateTime vipEndTime) {
        userMapper.vipRenewal(id,vipEndTime.plusDays(days));
    }

    @Override
    public void newVipRenewal(Integer id, Integer days, LocalDateTime vipStartTime, LocalDateTime vipEndTime) {
        userMapper.newVipRenewal(id,vipStartTime,vipStartTime.plusDays(days));
    }

    @Override
    public LocalDateTime getVipStartTime(Integer id) {
        return userMapper.getVipStartTime(id);
    }

    @Override
    public Integer getStatus(Integer id) {
        return userMapper.getStatus(id);
    }

    @Override
    public void recharge(Integer id, BigDecimal balance, BigDecimal amounts) {
        userMapper.recharge(id,amounts);
    }

    @Override
    public Integer findUserIdByPlateNumber(String plateNumber) {
        return userMapper.findUserIdByPlateNumber(plateNumber);
    }

    @Override
    public User findUserByLateNumber(String plateNumber) {
        return userMapper.findUserByPlateNumber(plateNumber);
    }

    @Override
    public BigDecimal getBalanceById(Integer userId) {
        return userMapper.getBalanceById(userId);
    }

    @Override
    public void deductions(Integer userId, BigDecimal moneys) {
        userMapper.deductions(userId,moneys);
    }

    @Override
    public User findUserByUserId(Integer userId) {
        return userMapper.findUserByUserId(userId);
    }

    @Override
    public void checkAndUpdateUserStatus() {
        List<User> userList = userMapper.findAllVip();
        for (User user : userList) {
            if (user.getVip_end_time().isBefore(LocalDateTime.now())) {
                user.setStatus(0);
                userMapper.updateStatus(user);
            }
        }
    }

    @Override
    public Integer getUserNumber() {
        return userMapper.getUserList().size();
    }

    @Override
    public Integer getTodayUserNumber() {
        LocalDate today = LocalDate.now();
        LocalDateTime startDateTime = today.atStartOfDay();
        LocalDateTime endDateTime = today.atTime(LocalTime.MAX);
        return userMapper.getTodayUserList(startDateTime,endDateTime).size();
    }
}
