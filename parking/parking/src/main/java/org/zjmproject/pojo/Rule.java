package org.zjmproject.pojo;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Rule {
    private Integer id;//主键
    @NotEmpty
    @Pattern(regexp = "^\\S{1,20}|[\\u4e00-\\u9fa5]{1,20}$")
    private String rule_name;//规则名称
    @NotNull
    @Min(0)
    private Integer free_time;//免费时长
    @NotNull
    @Min(1)
    private Integer charging_unit;//计费单位
    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal unit_cost;//单位时长的费用
    private LocalDateTime create_time;//创建时间
    private LocalDateTime update_time;//更新时间
}
