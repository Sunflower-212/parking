package org.zjmproject.pojo;

import java.math.BigDecimal;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Parking {
    private Integer id;//主键
    private Integer admin_id;//所属管理员id
    private String park_name;// 停车场名称
    private String park_address;//停车场地址
    private Integer park_mount;//总车位
    private Integer park_spare;//剩余车位
    private Integer park_storey;//层数
    private Integer rule_id;//计费规则
    private LocalDateTime create_time;//
    private LocalDateTime update_time;//



    private String admin_name;//管理员名称
    private String rule_name;//规则名称
    private Integer free_time;//免费时长
    private Integer charging_unit;//计费单位
    private BigDecimal unit_cost;//单位费用
}
