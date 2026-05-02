package com.integrations.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RedisService redisService;

    @Test
    void set_storesValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        redisService.set("key", "value", 600);

        verify(valueOperations).set("key", "value", Duration.ofSeconds(600));
    }

    @Test
    void get_returnsStoredValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("key")).thenReturn("stored-value");

        assertEquals("stored-value", redisService.get("key"));
    }

    @Test
    void get_returnsNullWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("missing")).thenReturn(null);

        assertNull(redisService.get("missing"));
    }

    @Test
    void delete_removesKey() {
        redisService.delete("key");

        verify(redisTemplate).delete("key");
    }
}
