package org.zjmproject.controller;


import java.math.BigDecimal;
import cn.hutool.core.lang.Dict;


import com.alipay.api.AlipayApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.URL;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.zjmproject.config.AliCloudConfig;
import org.zjmproject.pojo.*;
import org.zjmproject.service.AliPayService;
import org.zjmproject.service.RechargesService;
import org.zjmproject.service.UserService;
import org.zjmproject.service.PaymentService;
import org.zjmproject.service.AccessGuard;
import org.zjmproject.service.PlateUploadService;
import org.zjmproject.utils.AliOssUtil;
import org.zjmproject.utils.JwtUtil;
import org.zjmproject.utils.Md5Util;
import org.zjmproject.utils.ThreadLocalUtil;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@CrossOrigin
@RequestMapping("/user")
@Validated
public class UserController {
    @Autowired private PaymentService payments;
    @org.springframework.beans.factory.annotation.Value("${parking.integrations.oss:false}") private boolean ossEnabled;
    @Autowired
    private UserService userService;
    @Autowired
    private RechargesService rechargesService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AliPayService aliPayService;
    @Autowired
    private AliCloudConfig aliCloudConfig;

    //注册
    @Transactional
    @PostMapping("/register")
    public Result register(@RequestParam @Pattern(regexp = "^\\S{3,16}$") String username,
                           @RequestParam @Pattern(regexp = "^\\S{3,16}$") String password) {

        //查询用户
        User user = userService.findByUserName(username);
        if (user == null) {
            //注册
            userService.register(username, password);
            return Result.success();
        } else {
            //占用
            return Result.error("用户名已占用。。。");
        }
    }

    //登录
    @PostMapping("/login")
    public Result<String> login(@Pattern(regexp = "^\\S{3,16}$") String username, @Pattern(regexp = "^\\S{3,16}$") String password) {
        //查询用户名密码是否正确
        User loginUser = userService.findByUserName(username);
        if (loginUser == null) {
            return Result.error("用户不存在...");
        }

        if(Md5Util.checkPassword(password,loginUser.getPassword())) {
            if (Md5Util.isLegacy(loginUser.getPassword())) userService.updatePassword(password, loginUser.getId());
            Map<String, Object> claims = new HashMap<>();
            claims.put("id", loginUser.getId());
            claims.put("username", loginUser.getUser_name());
            claims.put("permissions",-1);
            String token = JwtUtil.genToken(claims);
            //把Token存储到redis中
            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
            //为了防止用户重复登陆导致生成多个token,将用户名作为key,前面加上user=是为了防止与管理员重名
            operations.set("user="+username,token,12, TimeUnit.HOURS);

            //登陆成功
            return Result.success(token);
        }
        return Result.error("密码错误");
    }

    //用户详情信息
    @GetMapping("/userInfo")
    public Result<User> userInfo(/*@RequestHeader(name = "Authorization") String token*/) {
        //根据token里面的用户名查询用户
        /*Map<String,Object> map = JwtUtil.parseToken(token);
        String username = (String) map.get("username");*/
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");

        User user = userService.findByUserName(username);
        return Result.success(user);
    }

    //更新用户信息
    @Transactional
    @PutMapping("/update")
    public Result update(@RequestBody @Validated User user) {
        userService.update(user);
        return Result.success();
    }

    //更新用户头像
    @Transactional
    @PatchMapping("/updateAvatar")
    public Result updateAvatar(@RequestParam @URL String avatarUrl) {
        userService.updateAvatar(avatarUrl);
        return Result.success();
    }

    //修改密码
    @Transactional
    @PatchMapping("updatePassword")
    public Result updatePassword(@RequestBody Map<String, String> params) {
        //校验参数
        String oldPwd = params.get("old_pwd");
        String newPwd = params.get("new_pwd");
        String rePwd = params.get("re_pwd");
        if (!StringUtils.hasLength(oldPwd) || !StringUtils.hasLength(newPwd) || !StringUtils.hasLength(rePwd)) {
            return Result.error("缺少必要的参数");
        }
        if (!rePwd.equals(newPwd)) {
            return Result.error("确认密码错误");
        }
//        if (newPwd.length() < 3 || newPwd.length() > 16) {
//            return Result.error("新密码长度不符合规范");
//        }
        if(!newPwd.matches("^\\S{3,16}$")){
            return Result.error("新密码长度不符合规范");
        }
        if(newPwd.equals(oldPwd)){
            return Result.error("新密码不能和旧密码相同");
        }
        //原密码是否正确
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        Integer userId = (Integer) map.get("id");
        User loginUser = userService.findByUserName(username);
        if(!Md5Util.checkPassword(oldPwd,loginUser.getPassword())){
            return Result.error("原密码错误");
        }
        //更新密码
        userService.updatePassword(newPwd,userId);
        //删除redis中对应的token
        ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
        operations.getOperations().delete("user="+username);
        return Result.success();
    }

    //重置密码
    @Transactional
    @PatchMapping("/resetPassword")
    public Result resetPassword(@RequestBody Map<String, String> params){
        String userName = params.get("user_name");
        Map<String,Object>map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=0&&permissions!=1){
            return Result.error("权限错误");
        }
        //查询用户
        User user = userService.findByUserName(userName);
        if(user==null){
            return  Result.error("用户不存在");
        }
        userService.updatePassword("123456",user.getId());
        stringRedisTemplate.delete("user="+userName);
        return Result.success();

    }

    //退出登录
    @GetMapping("/logout")
    public Result logout(){
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
        //删除redis中的token
        operations.getOperations().delete("user="+username);
        return Result.success();
    }

    //删除用户
    @Transactional
    @DeleteMapping("/delete")
    public Result deleteUser(String user_name){
        if(userService.findByUserName(user_name)==null){
            return Result.error("不存在该用户，删除失败");
        }
        Map<String, Object> map = ThreadLocalUtil.get();
        String l_username =(String) map.get("username");
        Integer permissions = (Integer) map.get("permissions");
        if((l_username!=null && l_username.equals(user_name))||permissions!=-1){
            userService.deleteUser(user_name);
            return Result.success();
        }
        return Result.error("非本人或者管理员，无法删除该用户");

    }

    //获取用户信息表
    @GetMapping("/pageList")
    public Result<PageBean<User>> pageList(
            Integer pageNum,
            Integer pageSize,
            @RequestParam(required = false) String plate_num,
            @RequestParam(required = false) String car_color,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String user_name){
        Map<String, Object> map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=-1){
            PageBean<User> pageBean = userService.pageListFind(pageNum,pageSize,plate_num,car_color,status,user_name);
            return Result.success(pageBean);
        }
        return Result.error("没有权限访问");
    }


    @GetMapping("/getBalance")
    public Result getBalance(){
        Map<String, Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer)  map.get("id");
        Integer permissions = (Integer) map.get("permissions");
        if(permissions==-1){
            BigDecimal balance = userService.getBalanceById(userId);
            return Result.success(balance);
        }
        return Result.error("权限错误");
    }

    //开通vip
    @Transactional
    @PatchMapping("/getVip")
    public Result getVip(@RequestParam Integer days){
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        User user = userService.findByUserName(username);
        Integer status = user.getStatus();
        Integer id =user.getId();
        if(status==0){
            if(days==null || days<=0 || days>3650) throw new IllegalArgumentException("会员天数需在1至3650之间");
            userService.getVip(id,days);
            return Result.success();
        }
        return Result.error("已经是会员");

    }

    //vip续费
    @Transactional
    @PatchMapping("/vipRenewal")
    public Result vipRenewal(@RequestParam Integer days){
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        User user = userService.findByUserName(username);
        Integer id = user.getId();
        Integer status = user.getStatus();
        LocalDateTime vipStartTime = user.getVip_start_time();
        LocalDateTime vipEndTime = user.getVip_end_time();
        if(days==null || days<=0 || days>3650) throw new IllegalArgumentException("会员天数需在1至3650之间");
        //正常续费
        if(status==1&&vipStartTime!=null&&vipEndTime!=null){
            userService.vipRenewal(id,days,vipEndTime);
            return Result.success();
        }
        //开过会员，但续费时已经过期
        if(status==0&&vipStartTime!=null&&vipEndTime!=null){
            vipStartTime = LocalDateTime.now();
            userService.newVipRenewal(id,days,vipStartTime,vipEndTime);
            return Result.success();
        }
        return Result.error("数据异常，请咨询管理员");
    }

    //充值
    @Transactional
    @PostMapping("/recharge")
    public Result recharge(@RequestParam BigDecimal amounts,@RequestParam Integer pay_method) throws Exception {
        return Result.success(payments.create(AccessGuard.userId(),amounts,pay_method));
    }

    //取消充值
    @Transactional
    @PostMapping("cancelRecharges")
    public Result cancelRecharges(@RequestParam Integer rechargesId) {
        payments.cancel(AccessGuard.userId(),rechargesId); return Result.success();
    }
    //充值成功，接收支付宝发送的数据
    @PostMapping("/rechargeNotify")
    public String rechargeNotify(HttpServletRequest request) throws Exception {
        Map<String,String> params=aliPayService.rechargePayNotify(request);
        if(params==null) return "failure";
        payments.confirm(params); return "success";
    }

    @PostMapping("/upload")
    public Result upload(MultipartFile file) throws Exception {
        if(!ossEnabled) throw new IllegalStateException("头像云存储尚未启用，请配置OSS服务");
        PlateUploadService.validate(file);
        //将文件存储到本地文件中
        String originalFilename=file.getOriginalFilename();
        //保证每次上传的文件名是唯一的，防止文件覆盖
        String fileName = UUID.randomUUID()+("image/png".equals(file.getContentType())?".png":".jpg");
        //file.transferTo(new File(path));
        String url = AliOssUtil.uploadFile(fileName,file.getInputStream(),aliCloudConfig.getAccessKeyId(),aliCloudConfig.getAccessKeySecret(),aliCloudConfig.getBucketName(),aliCloudConfig.getEndpoint());
        return Result.success(url);
    }

    @PostMapping("/addUser")
    public Result addUser(@RequestBody User user){
        Map<String,Object>map = ThreadLocalUtil.get();
        Integer permissions = (Integer) map.get("permissions");
        if(permissions!=0&&permissions!=1){
            return Result.error("权限错误");
        }
        //查询用户
        User finduser = userService.findByUserName(user.getUser_name());
        if (finduser == null) {
            //注册
            //用户名密码一致
            userService.register(user.getUser_name(), user.getUser_name());
            return Result.success();
        } else {
            //占用
            return Result.error("用户名已占用,请换个用户名添加！");
        }
    }
    /*
    * 批量导出用户
    * */
    @Transactional(readOnly=true)
    @GetMapping("/export")
    public void exportUsers(@RequestParam(required=false) String user_name,
                            @RequestParam(required=false) String plate_num,
                            @RequestParam(required=false) String car_color,
                            @RequestParam(required=false) Integer status,
                            HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition","attachment; filename=users.csv");
        var writer=response.getWriter();
        writer.write("\ufeff用户名,姓名,车牌,车辆颜色,手机号,会员状态\r\n");
        for(int page=1;;page++) {
            List<User> rows=userService.pageListFind(page,500,plate_num,car_color,status,user_name).getItems();
            for(User user:rows) {
                String[] values={user.getUser_name(),user.getName(),user.getPlate_num(),user.getCar_color(),user.getTel_num(),Integer.toString(user.getStatus())};
                for(int i=0;i<values.length;i++) {
                    if(i>0) writer.write(',');
                    String value=values[i]==null?"":values[i];
                    if(value.stripLeading().matches("^[=+@-].*")) value="'"+value;
                    writer.write('"');writer.write(value.replace("\"","\"\""));writer.write('"');
                }
                writer.write("\r\n");
            }
            if(rows.size()<500) break;
        }
        writer.flush();
    }

    @GetMapping("/data")
    public Result userData(){
        Map<String,Integer> userMap = new HashMap<>();
        userMap.put("userNumber",userService.getUserNumber());
        userMap.put("todayNewUserNumber",userService.getTodayUserNumber());
        return Result.success(userMap);
    }
}
