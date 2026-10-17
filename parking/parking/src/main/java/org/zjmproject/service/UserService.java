package org.zjmproject.service;


import java.math.BigDecimal;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.User;

import java.time.LocalDateTime;
import java.util.List;

public interface UserService {
    //根据用户名查询用户
    User findByUserName(String username);

    //注册
    void register(String username, String password);

    //更新用户信息
    void update(User user);

    //更新头像
    void updateAvatar(String avatarUrl);

    void updatePassword(String newPwd,Integer id);

    void deleteUser(String username);


    //条件分页列表查询
    PageBean<User> pageListFind(Integer pageNum, Integer pageSize, String plateNum, String carColor, Integer status,String userName);

    void getVip(Integer id,Integer days);

    LocalDateTime getVipEndTime(Integer id);

    void vipRenewal(Integer id, Integer days, LocalDateTime vipEndTime);

    void newVipRenewal(Integer id, Integer days, LocalDateTime vipStartTime, LocalDateTime vipEndTime);

    LocalDateTime getVipStartTime(Integer id);

    Integer getStatus(Integer id);


    void recharge(Integer id, BigDecimal balance, BigDecimal amounts);

    Integer findUserIdByPlateNumber(String plateNumber);

    User findUserByLateNumber(String plateNumber);

    BigDecimal getBalanceById(Integer userId);

    void deductions(Integer userId, BigDecimal moneys);

    User findUserByUserId(Integer userId);

    void checkAndUpdateUserStatus();

    Integer getUserNumber();

    Integer getTodayUserNumber();
}
