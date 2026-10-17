package org.zjmproject.pojo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Enter {
    private Integer id;
    private Integer space_id;
    private LocalDateTime reserved_at;
    private LocalDateTime expires_at;
    private String plate_num;//车牌号
    private Integer user_id;//用户id
    private Integer park_id;//停车场id
    private LocalDateTime enter_time;//入场时间
    private Integer flag;//标志是否离场，0-未入场，1-在场，2-已离开
    private Integer reservation;//标志是否预约


    private String user_name;//用户名
    private String park_name;//停车场名
    private Integer admin_id;
}
