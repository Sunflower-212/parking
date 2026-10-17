package org.zjmproject.pojo;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@NoArgsConstructor
@AllArgsConstructor
@Data
public class FormContent<T> {
    private T form;  // 表单内容
    private String contentType;  // 内容类型
    private BigDecimal reAmounts;//确认金额
    private Integer rechargeId;//确认充值id
    private String rechargeName;//充值名称
}
