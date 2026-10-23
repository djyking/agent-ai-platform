package com.opsagent.auth;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;

/** Test-only Redis substitute; real captcha generation, hashing, expiry and single-use consumption. */
final class IsolatedCaptchaRedis {
    private record Value(String digest, Instant expires) {}
    private final Map<String, Value> values = new ConcurrentHashMap<>();
    private final Map<String, Integer> windows = new ConcurrentHashMap<>();
    @SuppressWarnings("unchecked")
    StringRedisTemplate template() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        doAnswer(call -> {
            values.put(call.getArgument(0), new Value(call.getArgument(1), Instant.now().plus(call.getArgument(2, Duration.class))));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList()))
                .thenAnswer(call -> {
                    Value value = values.remove(((List<?>) call.getArgument(1)).get(0));
                    return value != null && value.expires().isAfter(Instant.now()) ? value.digest() : null;
                });
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyString(), anyString()))
                .thenAnswer(call -> {
                    String window = Instant.now().getEpochSecond() / 60 + ":" + ((List<?>) call.getArgument(1)).get(0);
                    return windows.merge(window, 1, Integer::sum) <= Integer.parseInt(call.getArgument(2)) ? 1L : 0L;
                });
        return redis;
    }
}
