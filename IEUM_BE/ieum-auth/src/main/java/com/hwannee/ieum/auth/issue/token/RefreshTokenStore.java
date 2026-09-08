package com.hwannee.ieum.auth.issue.token;

import com.hwannee.ieum.auth.issue.config.AuthProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private final StringRedisTemplate redis;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis, AuthProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public String issue(String uid) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        redis.opsForValue().set(key(token), uid, props.refreshTokenTtl());
        return token;
    }

    public Optional<String> consume(String token) {           // 한 번만 소비 = 회전
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key(token)));
    }

    public void revoke(String token) { redis.delete(key(token)); }

    private static String key(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}