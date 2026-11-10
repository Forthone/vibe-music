package com.vibemusic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.constant.JwtClaimsConstant;
import com.vibemusic.util.JwtUtil;
import com.vibemusic.util.ThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Cache authenticated results separately from other users and anonymous visitors. */
@Component("userScopedCacheKeyGenerator")
public class UserScopedCacheKeyGenerator implements KeyGenerator {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object generate(Object target, Method method, Object... params) {
        List<Object> arguments = new ArrayList<>();
        Map<String, Object> claims = null;
        boolean requestPresent = false;
        for (Object param : params) {
            if (param instanceof HttpServletRequest request) {
                requestPresent = true;
                String token = request.getHeader("Authorization");
                if (token != null && token.startsWith("Bearer ")) {
                    token = token.substring(7);
                }
                if (token != null && !token.isEmpty()) {
                    // Verify before cache lookup, including on cache hits.
                    claims = JwtUtil.parseToken(token);
                }
            } else {
                arguments.add(param);
            }
        }
        if (!requestPresent) {
            claims = ThreadLocalUtil.get();
            if (claims == null || claims.get(JwtClaimsConstant.USER_ID) == null) {
                throw new IllegalStateException("Authenticated user required for private cache");
            }
        }
        Object identity = claims == null ? "anonymous" : List.of(
                String.valueOf(claims.get(JwtClaimsConstant.ROLE)),
                String.valueOf(claims.get(JwtClaimsConstant.USER_ID)),
                String.valueOf(claims.get(JwtClaimsConstant.ADMIN_ID)));
        try {
            // Structured JSON avoids ambiguous delimiters in search fields.
            String payload = objectMapper.writeValueAsString(List.of(identity, arguments));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
            return "user-v3:" + method.getDeclaringClass().getName() + ":"
                    + method.getName() + ":" + digest;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot build user-scoped cache key", e);
        }
    }
}
