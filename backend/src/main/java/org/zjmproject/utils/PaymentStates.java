package org.zjmproject.utils;

public final class PaymentStates {
    private PaymentStates() {}
    public static String display(Integer flag, String businessType, String paymentState) {
        if ("MEMBERSHIP_DEBIT".equals(businessType)) return "DEBIT";
        if (Integer.valueOf(1).equals(flag)) return "PAID";
        if (Integer.valueOf(2).equals(flag)) return "CLOSED";
        return paymentState == null ? "VERIFYING" : paymentState;
    }
    public static boolean canClose(Integer flag, Integer method, String businessType, String paymentState) {
        return Integer.valueOf(0).equals(method) && !java.util.Set.of("PAID", "CLOSED", "DEBIT").contains(display(flag,businessType,paymentState));
    }
}
