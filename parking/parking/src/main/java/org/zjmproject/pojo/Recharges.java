package org.zjmproject.pojo;

import java.math.BigDecimal;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Recharges {
    private Integer id;
    private Integer user_id;
    private BigDecimal amounts;
    private Integer pay_method;
    private String pay_no;
    private Integer flag;
    private LocalDateTime pay_time;
    private LocalDateTime create_time;

    private String user_name;
}
