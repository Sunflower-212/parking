package org.zjmproject.pojo;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;


import java.time.LocalDateTime;

@Data
public class User {
    public static final String LICENSE_PLATE_REGEX = org.zjmproject.utils.LicensePlate.REGEX;
    @NotNull
    private Integer id;//主键ID
    private String user_name;//用户名
    @JsonIgnore
    private String password;//密码
    @NotEmpty
    @Pattern(regexp = "^\\S{1,10}|[\\u4e00-\\u9fa5]{1,10}$")
    private String name;//姓名
    @NotEmpty
    @Pattern(regexp = LICENSE_PLATE_REGEX)
    private String plate_num;//车牌号
    @NotEmpty
    @Pattern(regexp = "^[\\u4e00-\\u9fa5]{1,5}色$")
    private String car_color;//车辆颜色
    @NotEmpty
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    private String tel_num;//电话
    private BigDecimal balance;//账户余额
    private BigDecimal reserved_balance = BigDecimal.ZERO;
    public BigDecimal getAvailable_balance() {
        return balance == null ? BigDecimal.ZERO : balance.subtract(reserved_balance == null ? BigDecimal.ZERO : reserved_balance);
    }
    private int status;//身份
    private String user_pic;//用户头像
    private LocalDateTime vip_start_time;//vip开始时间
    private LocalDateTime vip_end_time;//vip结束时间
    private LocalDateTime create_time;//创建时间
    private LocalDateTime update_time;//更新时间
}
