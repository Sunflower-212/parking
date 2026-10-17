package org.zjmproject.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.AdminMapper;
import org.zjmproject.pojo.Admin;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.service.AdminService;
import org.zjmproject.utils.Md5Util;
import org.zjmproject.utils.ThreadLocalUtil;

import java.util.List;
import java.util.Map;
@Service
public class AdminServiceImpl implements AdminService {
    @Autowired
    private AdminMapper adminMapper;
    @Override
    public Admin findByAdminName(String adminname) {
        Admin admin = adminMapper.findByAdminName(adminname);
        return admin;
    }

    @Override
    public void register(String adminname, String password) {
        //加密
        String md5String = Md5Util.encode(password);
        //添加
        adminMapper.add(adminname,md5String);

    }

    @Override
    public void updatePassword(String newPwd) {
        Map<String,Object> map= ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        adminMapper.updatePassword(Md5Util.encode(newPwd),id);
    }

    @Override
    public PageBean<Admin> pageListFind(Integer pageNum, Integer pageSize, String adminName, Integer permissions) {
        //创建pageBean对象
        PageBean<Admin> pageBean = new PageBean<>();
        //开启分页查询 PageHelper
        org.zjmproject.utils.PageQuery.start(pageNum,pageSize);
        List<Admin> adminList = adminMapper.pageListFind(adminName,permissions);
        //Page中提供了方法，可获取PageHelper分页查询后，得到的总记录条数和当前页数据
        Page<Admin> page = (Page<Admin>) adminList;

        //把数据填充到PageBean中
        pageBean.setTotal(page.getTotal());
        pageBean.setItems(page.getResult());
        return pageBean;
    }

    @Override
    public List<Admin> adminList() {
        return adminMapper.adminList();
    }

    @Override
    public void updatePassword(String password, Integer adminId) {
        adminMapper.updatePassword(Md5Util.encode(password),adminId);
    }

    @Override
    public void deleteAdmin(String adminName) {
        adminMapper.deleteAdmin(adminName);
    }

    @Override
    public void register(String adminName, String password, Integer permissions) {
        //加密
        String md5String = Md5Util.encode(password);
        //添加
        adminMapper.addAdmin(adminName,md5String,permissions);
    }

    @Override
    public Integer findAdminNumber() {
        return adminMapper.findAdminNumber().size();
    }
}
