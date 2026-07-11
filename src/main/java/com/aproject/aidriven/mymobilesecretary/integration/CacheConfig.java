package com.aproject.aidriven.mymobilesecretary.integration;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 外部 API 結果的 Redis 快取:省費用、降延遲、扛外部服務抖動。
 *
 * TTL 依資料時效設定:
 * - weather:20 分鐘(36 小時預報,更新頻率低)
 * - travel-time:10 分鐘(路線時間短期內穩定)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer cacheTtlCustomizer() {
        // 值用 JSON 序列化(可讀、跨版本安全),不用 JDK 序列化。
        // 自組 ObjectMapper:JavaTimeModule 支援 Duration/Instant,
        // EVERYTHING 型別資訊讓反序列化能還原成原本的類別。
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        GenericJackson2JsonRedisSerializer.registerNullValueSerializer(mapper, null);
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        RedisCacheConfiguration json = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)));
        return builder -> builder
                .withCacheConfiguration("weather", json.entryTtl(Duration.ofMinutes(20)))
                .withCacheConfiguration("travel-time", json.entryTtl(Duration.ofMinutes(10)));
    }
}
