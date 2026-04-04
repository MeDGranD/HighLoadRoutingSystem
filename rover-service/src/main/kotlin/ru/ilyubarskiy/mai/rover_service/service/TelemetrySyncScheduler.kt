package ru.ilyubarskiy.mai.rover_service.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.ilyubarskiy.mai.rover_service.repository.RoverRepository
import ru.ilyubarskiy.mai.rover_service.repository.dto.RoverTelemetry
import ru.ilyubarskiy.mai.rover_service.repository.entity.RoverEntity
import java.time.Instant
import java.util.*

@Service
@EnableScheduling
class TelemetrySyncScheduler(
    private val roverRepository: RoverRepository,
    private val redisTemplate: RedisTemplate<String, RoverTelemetry>
) {

    @Scheduled(fixedRateString = "\${app.telemetry.sync-interval-ms:60000}")
    @SchedulerLock(
        name = "rover_position_sync",
        lockAtMostFor = "2m",
        lockAtLeastFor = "1m"
    )
    @Transactional
    fun syncTelemetryToDb() {

        val keys = redisTemplate.keys("rover:telemetry:*") ?: return

        keys.forEach { key ->
            val roverId = key.removePrefix("rover:telemetry:")
            val telemetry = redisTemplate.opsForValue().get(key) ?: return@forEach

            roverRepository.updateLastLocation(
                roverId = UUID.fromString(roverId),
                lat = telemetry.lat,
                lon = telemetry.lon,
                battery = telemetry.battery,
                ts = Instant.ofEpochMilli(telemetry.timestamp).toEpochMilli()
            )
        }
    }
}