package cn.har01d.alist_tvbox.config;

import com.alibaba.fastjson2.support.spring.data.redis.GenericFastJsonRedisSerializer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig extends CachingConfigurerSupport {

    /**
     * 添加自定义缓存异常处理 当缓存读写异常时,忽略异常
     */
    //@Override
    //public CacheErrorHandler errorHandler() {
    //    return new IgnoreExceptionCacheErrorHandler();
    //}

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<String, Object>();
        redisTemplate.setConnectionFactory(factory);
        GenericFastJsonRedisSerializer fastJsonRedisSerializer = new GenericFastJsonRedisSerializer();

        //FastJson2JsonRedisSerializer<Object> fastJson2JsonRedisSerializer =
        //        new FastJson2JsonRedisSerializer<>(Object.class);
        //ObjectMapper objectMapper = new ObjectMapper();
        //objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        //objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL,
        //        JsonTypeInfo.As.WRAPPER_ARRAY);
        //fastJson2JsonRedisSerializer.setObjectMapper(objectMapper);
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(stringRedisSerializer);// 设置key采用String的序列化方式
        redisTemplate.setHashKeySerializer(stringRedisSerializer);// 设置hash的key也采用String的序列化方式
        redisTemplate.setValueSerializer(fastJsonRedisSerializer); // 设置value采用的fastjson的序列化方式
        redisTemplate.setHashValueSerializer(fastJsonRedisSerializer);// 设置hash的value采用的fastjson的序列化方式
        redisTemplate.setDefaultSerializer(fastJsonRedisSerializer);// 设置其他默认的序列化方式为fastjson
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }

    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisSerializationContext.SerializationPair<Object> serializationPair = RedisSerializationContext.SerializationPair
                .fromSerializer(getRedisSerializer());
        RedisCacheConfiguration redisCacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30)).serializeValuesWith(serializationPair);
        return RedisCacheManager.builder(RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory))
                .cacheDefaults(redisCacheConfiguration).build();
    }

    private RedisSerializer<Object> getRedisSerializer() {
        return new GenericFastJsonRedisSerializer();
    }

}
