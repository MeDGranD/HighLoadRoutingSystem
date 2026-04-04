package ru.ilyubarskiy.mai.routing_service.service

import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class PriorityProvider(
    private val stringRedisTemplate: StringRedisTemplate
) {

    @Volatile
    private var priorityMap: Map<Int, Int> = emptyMap()
    @Volatile
    var isInitiated = false

    @Scheduled(fixedDelay = 60000)
    fun syncPriorityData() {

        val newPriorityMap = HashMap<Int, Int>()
        val ops = stringRedisTemplate.opsForHash<String, String>()

        val scanOptions = ScanOptions.scanOptions().count(1000).build()

        ops.scan("priority:edges", scanOptions).use { cursor ->
            while (cursor.hasNext()) {
                val entry = cursor.next()
                val edgeId = entry.key.toIntOrNull()
                val coefficient = entry.value.toIntOrNull()

                if (edgeId != null && coefficient != null) {
                    newPriorityMap[edgeId] = coefficient
                }
            }
        }

        priorityMap = newPriorityMap
    }

    fun getPriorityMultiplier(edgeId: Int): Int {
        if(!isInitiated) return 10
        return priorityMap[edgeId] ?: 0
    }

}