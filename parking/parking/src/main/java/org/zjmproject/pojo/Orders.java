package org.zjmproject.pojo;

import java.math.BigDecimal;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Orders {
    private Integer id;
    private String plate_num;//车牌号
    private Integer user_id;//用户id
    private Integer park_id;//停车场id
    private LocalDateTime enter_time;//入场时间
    private LocalDateTime exit_time;//离场时间
    private Integer parking_duration;//停车时长
    private BigDecimal moneys;//收费金额
    private LocalDateTime create_time;//创建时间


    private String user_name;//用户名
    private String park_name;//停车场名
    private String admin_name;//管理员名称

}
