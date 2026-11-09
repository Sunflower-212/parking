package org.zjmproject.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;


import java.time.LocalDateTime;
@Data
public class Admin {
    private Integer id;//主键id
    private String admin_name;//管理员账号
    @JsonIgnore//springmvc把当前对象转换成json字符串的时候，忽略password，前端接收到的数据就不会有password数值。
    private String password;//密码
    private int permissions;//管理员权限
    private LocalDateTime create_time;//创建时间
    private LocalDateTime update_time;//更新时间
}
