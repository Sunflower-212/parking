package org.zjmproject.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class Md5Util {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private Md5Util() {}
    public static String encode(String password) { return BCRYPT.encode(password); }
    public static boolean isLegacy(String hash) { return hash != null && hash.matches("[a-fA-F0-9]{32}"); }
    public static String getMD5String(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static boolean checkPassword(String password, String hash) {
        if (password == null || hash == null) return false;
        return isLegacy(hash) ? MessageDigest.isEqual(getMD5String(password).getBytes(StandardCharsets.US_ASCII), hash.toLowerCase().getBytes(StandardCharsets.US_ASCII)) : BCRYPT.matches(password, hash);
    }
}
