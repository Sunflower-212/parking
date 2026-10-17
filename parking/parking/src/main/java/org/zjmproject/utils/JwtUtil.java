package org.zjmproject.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public final class JwtUtil {
    private static volatile Algorithm algorithm;
    public JwtUtil(@Value("${parking.jwt-secret}") String secret) {
        if (secret == null || secret.length() < 32 || secret.startsWith("replace-")) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 characters");
        algorithm = Algorithm.HMAC256(secret);
    }
    public static String genToken(Map<String,Object> claims) {
        return JWT.create().withJWTId(UUID.randomUUID().toString()).withClaim("claims",claims).withExpiresAt(Instant.now().plusSeconds(43200)).sign(algorithm);
    }
    public static Map<String,Object> parseToken(String token) {
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        return JWT.require(algorithm).build().verify(token).getClaim("claims").asMap();
    }
}
