package org.zjmproject.utils;
import java.util.Locale;
public final class LicensePlate {
    private LicensePlate() {}
    public static final String PROVINCES="京津沪渝冀晋蒙辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新";
    public static final String REGEX="["+PROVINCES+"][A-HJ-NP-Z]-(?:(?=[A-HJ-NP-Z0-9]{5}$)[0-9]*(?:[A-HJ-NP-Z][0-9]*){0,2}|[A-HJ-NP-Z][A-HJ-NP-Z0-9][0-9]{4}|[0-9]{5}[A-HJ-NP-Z])";
    public static String normalize(String value) {
        if(value==null)return null;
        String plate=value.strip().toUpperCase(Locale.ROOT);
        if(plate.matches("["+PROVINCES+"][A-HJ-NP-Z][A-HJ-NP-Z0-9]{5,6}"))plate=plate.substring(0,2)+"-"+plate.substring(2);
        return plate;
    }
}
