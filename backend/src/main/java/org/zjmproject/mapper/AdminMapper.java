package org.zjmproject.mapper;

import org.apache.ibatis.annotations.*;
import org.zjmproject.pojo.Admin;

import java.util.List;

@Mapper
public interface AdminMapper {
    //根据用户名查询用户
    @Select("select * from admin where admin_name=#{adminname}")
    Admin findByAdminName(String adminname);

    //添加
    @Insert("insert into admin(admin_name,password,create_time,update_time)" +
            " values(#{adminname},#{password},now(),now())")
    void add(String adminname, String password);

    //修改密码
    @Update("update admin set password=#{password},update_time=now() where id=#{id}")
    void updatePassword(String password,Integer id);

    List<Admin> pageListFind(String adminName, Integer permissions);

    @Select("select * from admin")
    List<Admin> adminList();

    @Delete("delete from admin where admin_name=#{adminName}")
    void deleteAdmin(String adminName);

    @Insert("insert into admin(admin_name,password,permissions,create_time,update_time)" +
            " values(#{adminName},#{password},#{permissions},now(),now())")
    void addAdmin(String adminName, String password, Integer permissions);

    @Select("select * from admin where permissions=0")
    List<Admin> findAdminNumber();
}
