package ru.ilyubarskiy.mai.routing_service.service

import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class TrafficProvider(
    private val stringRedisTemplate: StringRedisTemplate
) {
    @Volatile
    private var trafficMap: Map<Int, Int> = emptyMap()

    @Scheduled(fixedDelay = 60000)
    fun syncTrafficData() {

        val newTrafficMap = HashMap<Int, Int>()
        val ops = stringRedisTemplate.opsForHash<String, String>()

        val scanOptions = ScanOptions.scanOptions().count(1000).build()

        ops.scan("traffic:edges", scanOptions).use { cursor ->
            while (cursor.hasNext()) {
                val entry = cursor.next()
                val edgeId = entry.key.toIntOrNull()
                val coefficient = entry.value.toIntOrNull()

                if (edgeId != null && coefficient != null) {
                    newTrafficMap[edgeId] = coefficient
                }
            }
        }

        trafficMap = newTrafficMap
    }

    fun getTrafficMultiplier(edgeId: Int): Int {
        return trafficMap[edgeId] ?: 0
    }

}