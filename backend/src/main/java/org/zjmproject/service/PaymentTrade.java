package org.zjmproject.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Created only from signature-verified notifications or the configured Alipay SDK client. */
public record PaymentTrade(String orderNo, String tradeNo, String status,
                           BigDecimal amount, LocalDateTime paidAt) {
    public boolean paid() { return "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status); }
}
