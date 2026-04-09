package ru.paycore.gateway.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import ru.paycore.gateway.domain.model.PaymentResponse;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, PaymentResponse> redisTemplate(RedisConnectionFactory factory) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<PaymentResponse> valueSerializer =
            new Jackson2JsonRedisSerializer<>(mapper, PaymentResponse.class);

        RedisTemplate<String, PaymentResponse> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(valueSerializer);
        return template;
    }
}
