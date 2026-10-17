package org.zjmproject.mapper;


import java.math.BigDecimal;
import org.apache.ibatis.annotations.*;
import org.zjmproject.pojo.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {
    @Select("select count(*) from user") Long countUsers();
    @Select("select count(*) from user where create_time>=#{from} and create_time<#{to}") Long countNewUsers(LocalDateTime from,LocalDateTime to);
    @Update("update user set status=0,update_time=now() where status=1 and vip_end_time<#{now} order by vip_end_time,id limit 500") int expireVip(LocalDateTime now);
    //根据用户名查询用户
    @Select("select * from user where user_name=#{username}")
    User findByUserName(String username);

    @Select("select * from user where id=#{id}")
    User selectByUserId(Integer id);
    //添加
    @Insert("insert into user(user_name,password,create_time,update_time)" +
            " values(#{username},#{password},now(),now())")
    void add(String username, String password);

    @Update("update user set name=#{name},plate_num=#{plate_num},car_color=#{car_color}," +
            "tel_num=#{tel_num},update_time=#{update_time} where id=#{id}")
    void update(User user);

    @Update("update user set user_pic=#{avatarUrl},update_time=now() where id=#{id}")
    void updateAvatar(String avatarUrl,Integer id);

    @Update("update user set password=#{password},update_time=now() where id=#{id}")
    void updatePassword(String password, Integer id);

    @Delete("delete from user where user_name=#{username}")
    void deleteUser(String username);

    List<User> pageListFind(String plateNum, String carColor, Integer status,String userName);

    @Update("update user set status=1,vip_start_time=#{now},vip_end_time=#{localDateTime},update_time=now() where id=#{id}")
    void updateVipData(Integer id, LocalDateTime now, LocalDateTime localDateTime);

    @Select("select vip_end_time from user where id=#{id}")
    LocalDateTime getVipEndTime(Integer id);

    @Update("update user set vip_end_time=#{vipEndTime},update_time=now() where id=#{id}")
    void vipRenewal(Integer id,LocalDateTime vipEndTime);

    @Update("update user set vip_start_time=#{vipStartTime},vip_end_time=#{localDateTime},update_time=now() where id=#{id}")
    void newVipRenewal(Integer id, LocalDateTime vipStartTime, LocalDateTime localDateTime);
    @Select("select vip_start_time from user where id=#{id}")
    LocalDateTime getVipStartTime(Integer id);

    @Select("select status from user where id=#{id}")
    Integer getStatus(Integer id);

    @Update("update user set balance=balance+#{newBalance},update_time=now() where id=#{id}")
    void recharge(Integer id, BigDecimal newBalance);

    @Select("select id from user where plate_num=#{plateNumber}")
    Integer findUserIdByPlateNumber(String plateNumber);

    @Select("select * from user where plate_num=#{plateNumber}")
    User findUserByPlateNumber(String plateNumber);

    @Select("select balance from user where id=#{userId}")
    BigDecimal getBalanceById(Integer userId);

    @Update("update user set balance= balance-#{moneys} where id=#{userId}")
    void deductions(Integer userId, BigDecimal moneys);

    @Select("select * from user where id=#{userId}")
    User findUserByUserId(Integer userId);

    @Select("select * from user where status=1")
    List<User> findAllVip();

    @Update("update user set status=#{status} where id=#{id}")
    void updateStatus(User user);

    @Select("select * from user")
    List<User> getUserList();

    @Select("select * from user where create_time>=#{startDateTime} and create_time<#{endDateTime}")
    List<User> getTodayUserList(LocalDateTime startDateTime, LocalDateTime endDateTime);
}
