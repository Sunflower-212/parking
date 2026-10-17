package org.zjmproject.controller;

import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.*;
import org.zjmproject.service.AdminService;
import org.zjmproject.utils.JwtUtil;
import org.zjmproject.utils.Md5Util;
import org.zjmproject.utils.ThreadLocalUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
@CrossOrigin
@RestController
@RequestMapping("/admin")
@Validated
public class AdminController {
    @Autowired
    private AdminService adminService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @PostMapping("/login")
    public Result<String> login(@Pattern(regexp = "^\\S{3,16}$") String username, @Pattern(regexp = "^\\S{3,16}$") String password){
        String adminname=username;
        Admin loginAdmin = adminService.findByAdminName(adminname);
        if(loginAdmin==null){
            return Result.error("管理员不存在...");
        }
        if(Md5Util.checkPassword(password,loginAdmin.getPassword())){
            if (Md5Util.isLegacy(loginAdmin.getPassword())) adminService.updatePassword(password, loginAdmin.getId());
            Map<String,Object> claims = new HashMap<>();
            claims.put("id",loginAdmin.getId());
            claims.put("adminname",loginAdmin.getAdmin_name());
            claims.put("permissions",loginAdmin.getPermissions());
            String token = JwtUtil.genToken(claims);

            //存储token到redis中
            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
            operations.set("admin="+adminname,token,12, TimeUnit.HOURS);
            //登陆成功
            return  Result.success(token);
        }
        return Result.error("密码错误");
    }

    @Transactional
    @PostMapping("/register")
    public Result register(@Pattern(regexp = "^\\S{3,16}$") String adminname, @Pattern(regexp = "^\\S{3,16}$")String password){
        Admin admin = adminService.findByAdminName(adminname);
        if(admin==null){
            adminService.register(adminname,password);
            return Result.success();
        }else {
            //占用
            return Result.error("管理员姓名已占用...");
        }
    }
    @GetMapping("/adminInfo")
    public Result<Admin> adminInfo(){
        Map<String,Object> map = ThreadLocalUtil.get();
        String adminName = (String) map.get("adminname");
        Admin admin = adminService.findByAdminName(adminName);
        return Result.success(admin);
    }

    @Transactional
    @PatchMapping("updatePassword")
    public Result updatePassword(@RequestBody Map<String, String> params) {
        //校验参数
        String newPwd = params.get("new_pwd");
        String rePwd = params.get("re_pwd");
        if (!StringUtils.hasLength(newPwd) || !StringUtils.hasLength(rePwd)) {
            return Result.error("缺少必要的参数");
        }
        if (!rePwd.equals(newPwd)) {
            return Result.error("确认密码错误");
        }
        if(!newPwd.matches("^\\S{3,16}$")){
            return Result.error("新密码长度不符合规范");
        }
        //管理员不用判断原密码是否正确原密码是否正确
        Map<String, Object> map = ThreadLocalUtil.get();
        String adminname = (String) map.get("adminname");
        var current=adminService.findByAdminName(adminname);
        if(current==null || !Md5Util.checkPassword(params.get("old_pwd"),current.getPassword())) return Result.error("原密码错误");
        if(newPwd.equals(params.get("old_pwd"))) return Result.error("新密码不能与原密码相同");
        adminService.updatePassword(newPwd);
        //删除redis中对应的token
        ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
        operations.getOperations().delete("admin="+adminname);
        return Result.success();
    }

    @GetMapping("/logout")
    public Result logout(){
        Map<String, Object> map = ThreadLocalUtil.get();
        String adminname = (String) map.get("adminname");
        ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
        //删除redis中的token
        operations.getOperations().delete("admin="+adminname);
        return Result.success();
    }

    @GetMapping("/pageList")
    public Result<PageBean<Admin>> pageList(Integer pageNum,
                                            Integer pageSize,
                                            @RequestParam(required = false) String admin_name,
                                            @RequestParam(required = false) Integer permissions){
        Map<String, Object> map = ThreadLocalUtil.get();
        Integer login_permissions = (Integer) map.get("permissions");
        if(login_permissions!=1){
            return Result.error("权限不足");
        }
        PageBean<Admin> pageBean = adminService.pageListFind(pageNum,pageSize,admin_name,permissions);
        return Result.success(pageBean);
    }
    @GetMapping("/list")
    public Result<List<Admin>> adminList(){
        Map<String, Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1){
            return Result.error("非超级管理员无法获取管理员列表");
        }
        List<Admin> adminList = adminService.adminList();
        return Result.success(adminList);
    }


    //重置密码
    @Transactional
    @PatchMapping("/resetPassword")
    public Result resetPassword(@RequestBody Map<String, String> params){
        String adminName = params.get("admin_name");
        Map<String,Object>map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1){
            return Result.error("权限错误");
        }
        //查询用户
        Admin admin = adminService.findByAdminName(adminName);
        if(admin==null){
            return  Result.error("管理员不存在");
        }
        adminService.updatePassword("123456",admin.getId());
        stringRedisTemplate.delete("admin="+adminName);
        return Result.success();

    }

    //删除管理员
    @Transactional
    @DeleteMapping("/delete")
    public Result deleteAdmin(String admin_name){
        if(adminService.findByAdminName(admin_name)==null){
            return Result.error("不存在该管理员，删除失败");
        }
        Map<String, Object> map = ThreadLocalUtil.get();
        String l_adminname =(String) map.get("adminname");
        Integer permissions = (Integer) map.get("permissions");
        if(l_adminname!=null && l_adminname.equals(admin_name)){
            return Result.error("不能删除自己");
        }
        if(permissions!=1){
            return Result.error("非超级管理员不能删除");
        }
        adminService.deleteAdmin(admin_name);
        stringRedisTemplate.delete("admin="+admin_name);
        return Result.success();
    }

    @Transactional
    @PostMapping("/addAdmin")
    public Result addAdmin(@RequestBody Admin admin){
        Map<String,Object>map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1){
            return Result.error("非超级管理员，不能添加");
        }
        if(admin.getAdmin_name()==null || !admin.getAdmin_name().matches("^\\S{3,16}$")) throw new IllegalArgumentException("管理员账号需为3至16位非空白字符");
        Admin findAdmin = adminService.findByAdminName(admin.getAdmin_name());
        if(findAdmin!=null){
            return  Result.error("该管理员已存在，请换个管理员名添加");
        }
        if(admin.getPermissions()!=0 && admin.getPermissions()!=1) throw new IllegalArgumentException("管理员权限无效");
        //用户名密码一致
        adminService.register(admin.getAdmin_name(),admin.getAdmin_name(),admin.getPermissions());
        return Result.success();
    }

    @GetMapping("/number")
    public Result number(){
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=1){
            return Result.error("权限错误");
        }
        Integer number = adminService.findAdminNumber();
        return Result.success(number);
    }


}
