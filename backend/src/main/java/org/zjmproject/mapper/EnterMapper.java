package org.zjmproject.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.zjmproject.pojo.Enter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface EnterMapper {
    @Select("select count(*) from enter where enter_time>=#{from} and enter_time<#{to} and flag<>0") int countToday(LocalDateTime from,LocalDateTime to);
    @Select("select count(*) from enter where park_id=#{park} and enter_time>=#{from} and enter_time<#{to} and flag<>0") int countTodayPark(int park,LocalDateTime from,LocalDateTime to);


    @Insert("insert into enter(plate_num, user_id, park_id, enter_time, flag, reservation, update_time) " +
            "VALUES (#{plateNumber},#{userId},#{parkId},now(),1,0,now())")
    void add(String plateNumber, Integer userId, Integer parkId);

    @Insert("insert into enter(plate_num, user_id, park_id, enter_time, flag, reservation, update_time) " +
            "VALUES (#{plate_num},#{user_id},#{park_id},now(),#{flag},#{reservation},now())")
    void addEnter(Enter enter);
    @Select("select * from enter where plate_num=#{plateNumber} and flag=#{flag}")
    Enter isVehicleInParkingLot(String plateNumber, Integer flag);

    @Update("update enter set flag=#{flag},update_time=now() where id=#{id}")
    void updateFlag(Enter enter);

    List<Enter> userGetPageList(String parkName, Integer userId, LocalDateTime startDateTime, LocalDateTime endDateTime);

    List<Enter> adminGetPageList(Integer adminId, String plateNum, String parkName, Integer flag);

    List<Enter> rootGetPageList(String plateNum, String parkName, Integer flag, String adminName);

    @Select("select  * from enter where park_id=#{parkingId}")
    List<Enter> findEnterListByParkingId(Integer parkingId);

    @Select("select * from enter where plate_num=#{plateNumber} and flag=#{flag} and reservation=#{reservation}")
    Enter isReservationParkingLot(String plateNumber, Integer flag, Integer reservation);

    @Update("update enter set plate_num=#{plate_num},user_id=#{user_id},park_id=#{park_id},enter_time=#{enter_time},flag=#{flag},reservation=#{reservation} where id=#{id}")
    void update(Enter enterRes);

    List<Enter> isVehicle(Integer userId, Integer flag);

    List<Enter> isReservation(Integer userId, Integer flag, Integer reservation);

    @Select("select * from enter where id=#{id}")
    Enter findEnterById(Integer id);

    @Select("select * from enter where enter_time>=#{startDateTime} and enter_time<#{endDateTime} and flag!=0")
    List<Enter> findTodayEnter(LocalDateTime startDateTime, LocalDateTime endDateTime);

    @Select("select * from enter where enter.park_id=#{parkId} and enter_time>=#{startDateTime} and enter_time<#{endDateTime} and flag!=0")
    List<Enter> findTodayEnterByParkId(Integer parkId, LocalDateTime startDateTime, LocalDateTime endDateTime);

    @Select("select * from enter where flag=0 and reservation=1")
    List<Enter> findEnterListByFlagAndReservation();
}
