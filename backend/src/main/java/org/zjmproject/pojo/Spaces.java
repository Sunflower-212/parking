package org.zjmproject.pojo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Spaces {
    private Integer id;
    private Integer active_enter_id;//主键id
    private Integer park_id;//停车场id
    private String spaces_name;//车位名称
    private Integer spaces_col;//列
    private Integer spaces_row;//行
    private Integer spaces_storey;//层数
    private Integer spaces_flag;//车位标识（0-空闲车位，1-被占用车位，2-道路，3-墙壁，4-出入口）
    private String user_name;//使用者名字
    private LocalDateTime create_time;//创建时间
    private LocalDateTime update_time;//更新时间

    private String park_name;//停车场名称


}
