package org.zjmproject.pojo;
import jakarta.validation.constraints.*;
public record CreateUserRequest(
 @NotBlank @Pattern(regexp="^\\S{3,16}$") String user_name,
 @Size(max=10) String name,
 @Pattern(regexp="^$|"+User.LICENSE_PLATE_REGEX) String plate_num,
 @Pattern(regexp="^$|[\\u4e00-\\u9fa5]{1,5}色") String car_color,
 @Pattern(regexp="^$|1[3-9]\\d{9}") String tel_num) {}
