package org.zjmproject.mapper;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.zjmproject.pojo.Orders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrdersMapper {
    @Select("select count(*) from orders where create_time>=#{from} and create_time<#{to}") int countToday(LocalDateTime from,LocalDateTime to);
    @Select("select count(*) from orders where park_id=#{park} and create_time>=#{from} and create_time<#{to}") int countTodayPark(int park,LocalDateTime from,LocalDateTime to);

    @Insert("insert into orders(plate_num, user_id, park_id, enter_time, exit_time, parking_duration, moneys, create_time) " +
            "values (#{plateNumber},#{userId},#{parkId},#{enterTime},#{exitTime},#{parkingDuration},#{moneys},now())")
    void addOrder(String plateNumber, Integer userId, Integer parkId, LocalDateTime enterTime, LocalDateTime exitTime, Integer parkingDuration, BigDecimal moneys);


    List<Orders> findOrdersByUserId(Integer userId);

    List<Orders> adminGetPageList(String userName, String parkName, LocalDateTime startDateTime,LocalDateTime endDateTime, Integer adminId);

    List<Orders> rootGetPageList(String userName, String parkName, LocalDateTime startDateTime, LocalDateTime endDateTime, String adminName);

    List<Orders> userGetOrdersList(Integer userId, String parkName, LocalDateTime startTime, LocalDateTime endTime);

    @Select("select  * from orders where park_id=#{parkingId}")
    List<Orders> findOrdersListByParkingId(Integer parkingId);

    @Select("select  * from orders where create_time>=#{startDateTime} and create_time<#{endDateTime}")
    List<Orders> findTodayExit(LocalDateTime startDateTime, LocalDateTime endDateTime);

    @Select("select  * from orders where park_id=#{parkId} and create_time>=#{startDateTime} and create_time<#{endDateTime}")
    List<Orders> findTodayExitByParkId(Integer parkId, LocalDateTime startDateTime, LocalDateTime endDateTime);
}
