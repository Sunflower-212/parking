package org.zjmproject.mapper;

import org.apache.ibatis.annotations.*;
import org.zjmproject.pojo.Parking;

import java.util.List;

@Mapper
public interface ParkingMapper {

    @Insert("insert into parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time)" +
            " values (#{admin_id},#{park_name},#{park_address},#{park_mount},#{park_spare},#{park_storey},#{rule_id},now(),now())")
    void addParking(Parking parking);

    @Update("update parking set admin_id=#{admin_id},park_name=#{park_name},park_address=#{park_address}," +
            "park_storey=#{park_storey},rule_id=#{rule_id},update_time=now() where id=#{id}")
    void updateParking(Parking parking);

    List<Parking> findAllParking(String parkName, String parkAddress, String adminName, String ruleName);

    List<Parking> findParking(String parkName, String parkAddress, String ruleName, Integer adminId);

    @Select("select * from parking where park_name=#{parkName}")
    Parking findParkingByParkingName(String parkName);

    @Delete("delete from parking where park_name=#{parkName}")
    void deleteParkingByParkingName(String parkName);

    @Select("select id from parking where park_name=#{parkName}")
    Integer findParkIdByParkName(String parkName);

    Parking findParkByParkName(String parkName);

    @Update("update parking set park_spare=park_spare+1 where id=#{parkId}")
    void addParkSpare(Integer parkId);

    @Update("update parking set park_spare=park_spare-1 where id=#{parkId}")
    void reduceParkSpare(Integer parkId);

    String getAdminNameByParkName(String parkName);

    @Select("select * from parking")
    List<Parking> parkingList();

    @Select("select * from parking where admin_id=#{adminId}")
    List<Parking> adminGetParkingList(Integer adminId);

    @Select("select * from parking where rule_id = #{ruleId}")
    List<Parking> findParkByRuleId(Integer ruleId);

    @Select("select * from parking where id=#{id}")
    Parking findParkingById(Integer id);

    Parking findParkById(Integer parkId);
}
