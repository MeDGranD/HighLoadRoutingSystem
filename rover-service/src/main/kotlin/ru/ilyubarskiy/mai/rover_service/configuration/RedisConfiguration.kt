package ru.ilyubarskiy.mai.rover_service.configuration

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.scheduling.annotation.EnableScheduling
import ru.ilyubarskiy.mai.rover_service.repository.dto.RoverTelemetry

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "2m")
class RedisConfiguration {
    @Bean
    fun redisTemplate(factory: RedisConnectionFactory): RedisTemplate<String, RoverTelemetry> {
        val template = RedisTemplate<String, RoverTelemetry>()
        template.connectionFactory = factory
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = Jackson2JsonRedisSerializer(RoverTelemetry::class.java)
        return template
    }

    @Bean
    fun lockProvider(connectionFactory: RedisConnectionFactory): LockProvider {
        return RedisLockProvider(connectionFactory, "routing-lock")
    }
}