package org.zjmproject.service;

import com.alipay.api.AlipayApiException;
import java.util.Set;

/** Only bounded, allowlisted diagnostics may leave the payment adapter. */
public final class PaymentProviderFailure extends AlipayApiException {
    private static final Set<String> AUTH = Set.of("40001", "40002", "40006", "isv.invalid-signature", "isv.invalid-app-id", "isv.app-id-not-exist", "isv.insufficient-isv-permissions", "isv.invalid-app-auth-token", "aop.invalid-app-auth-token", "aop.invalid-app-auth-token-no-api", "ACQ.ACCESS_FORBIDDEN");
    private static final Set<String> CODES = Set.of("20000", "40004", "ACQ.SYSTEM_ERROR", "ACQ.TRADE_NOT_EXIST", "ACQ.TRADE_HAS_SUCCESS", "ACQ.TRADE_STATUS_ERROR", "ACQ.INVALID_PARAMETER", "isv.invalid-parameter", "isv.missing-parameter");
    private final String category, providerCode;
    private PaymentProviderFailure(String category,String providerCode) {
        super(category);this.category=category;this.providerCode=providerCode;
    }
    public String category(){return category;}
    public String providerCode(){return providerCode;}
    public static PaymentProviderFailure response(String code,String subCode) {
        String safe=AUTH.contains(subCode==null?"":subCode) || CODES.contains(subCode==null?"":subCode)?subCode:
            AUTH.contains(code==null?"":code) || CODES.contains(code==null?"":code)?code:"UNCLASSIFIED";
        return new PaymentProviderFailure(AUTH.contains(safe)?"PROVIDER_AUTH":"PROVIDER_SERVICE",safe);
    }
    public static PaymentProviderFailure from(Throwable error) {
        Throwable current=error;
        for(int depth=0;current!=null && depth<12;depth++,current=current.getCause()) {
            if(current instanceof PaymentProviderFailure known)return known;
            if(current instanceof java.net.SocketTimeoutException || current instanceof java.util.concurrent.TimeoutException)
                return new PaymentProviderFailure("PROVIDER_TIMEOUT","UNCLASSIFIED");
            if(current instanceof java.io.IOException)return new PaymentProviderFailure("PROVIDER_NETWORK","UNCLASSIFIED");
            if(current instanceof AlipayApiException sdk && sdk.getErrCode()!=null && AUTH.contains(sdk.getErrCode()))
                return response(sdk.getErrCode(),null);
        }
        return new PaymentProviderFailure("PROVIDER_UNAVAILABLE","UNCLASSIFIED");
    }
}
