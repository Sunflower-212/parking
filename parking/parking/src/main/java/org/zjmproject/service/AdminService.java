package org.zjmproject.service;

import org.zjmproject.pojo.Admin;
import org.zjmproject.pojo.PageBean;

import java.util.List;

public interface AdminService {
    //根据用户名查询用户
    Admin findByAdminName(String adminname);

    //注册
    void register(String adminname, String password);

    void updatePassword(String newPwd);

    PageBean<Admin> pageListFind(Integer pageNum, Integer pageSize,String adminName, Integer permissions);

    List<Admin> adminList();

    void updatePassword(String password, Integer adminId);

    void deleteAdmin(String adminName);

    void register(String adminName, String password, Integer permissions);

    Integer findAdminNumber();
}
