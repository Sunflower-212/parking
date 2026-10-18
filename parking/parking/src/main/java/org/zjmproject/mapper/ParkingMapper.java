package org.zjmproject.mapper;

import org.apache.ibatis.annotations.*;
import org.zjmproject.pojo.Parking;

import java.util.List;

@Mapper
public interface ParkingMapper {

    @Insert("insert into parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time)" +
            " values (#{admin_id},#{park_name},#{park_address},#{park_mount},#{park_spare},#{park_storey},#{rule_id},now(),now())")
    @Options(useGeneratedKeys=true,keyProperty="id")
    void addParking(Parking parking);

    @Update("update parking set admin_id=#{admin_id},park_name=#{park_name},park_address=#{park_address}," +
            "park_storey=#{park_storey},rule_id=#{rule_id},update_time=now() where id=#{id}")
    void updateParking(Parking parking);

    List<Parking> findAllParking(@Param("parkName") String parkName, @Param("parkAddress") String parkAddress, @Param("adminName") String adminName, @Param("ruleName") String ruleName);

    List<Parking> findParking(@Param("parkName") String parkName, @Param("parkAddress") String parkAddress, @Param("ruleName") String ruleName, @Param("adminId") Integer adminId);

    @Select("select p.id,p.admin_id,p.park_name,p.park_address,c.park_mount,c.park_spare,p.park_storey,p.rule_id,p.create_time,p.update_time from parking p join parking_capacity_total c on c.park_id=p.id where p.park_name=#{parkName}")
    Parking findParkingByParkingName(String parkName);

    @Delete("delete from parking where park_name=#{parkName}")
    void deleteParkingByParkingName(String parkName);

    @Select("select id from parking where park_name=#{parkName}")
    Integer findParkIdByParkName(String parkName);

    Parking findParkByParkName(String parkName);



    String getAdminNameByParkName(String parkName);

    @Select("select p.id,p.admin_id,p.park_name,p.park_address,c.park_mount,c.park_spare,p.park_storey,p.rule_id,p.create_time,p.update_time from parking p join parking_capacity_total c on c.park_id=p.id")
    List<Parking> parkingList();

    @Select("select p.id,p.admin_id,p.park_name,p.park_address,c.park_mount,c.park_spare,p.park_storey,p.rule_id,p.create_time,p.update_time from parking p join parking_capacity_total c on c.park_id=p.id where p.admin_id=#{adminId}")
    List<Parking> adminGetParkingList(Integer adminId);

    @Select("select p.id,p.admin_id,p.park_name,p.park_address,c.park_mount,c.park_spare,p.park_storey,p.rule_id,p.create_time,p.update_time from parking p join parking_capacity_total c on c.park_id=p.id where p.rule_id=#{ruleId}")
    List<Parking> findParkByRuleId(Integer ruleId);

    @Select("select p.id,p.admin_id,p.park_name,p.park_address,c.park_mount,c.park_spare,p.park_storey,p.rule_id,p.create_time,p.update_time from parking p join parking_capacity_total c on c.park_id=p.id where p.id=#{id}")
    Parking findParkingById(Integer id);

    Parking findParkById(Integer parkId);
}
