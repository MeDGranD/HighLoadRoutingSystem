package ru.ilyubarskiy.mai.planner_global.service.overlay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.planner_global.domen.kafka.RegionWeightsUpdate
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import java.util.concurrent.CopyOnWriteArrayList

@Service
@EnableScheduling
class OverlayGraphUpdater(
    private val graphState: OverlayGraphState,
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val mapper = jacksonObjectMapper()

    private val METRIC_TTL_MILLIS = 360_000L

    @EventListener(ApplicationReadyEvent::class)
    fun loadInitialStateFromRedis() {
        log.info("loading last seen weights from Redis")

        val keys = redisTemplate.keys("region_weights:*") ?: emptySet()

        for (key in keys) {
            val payload = redisTemplate.opsForValue().get(key)
            if (payload != null) {
                consumeRegionUpdates(payload)
            }
        }
        log.info("State of graph recreated. Rebuilding graph set to Kafka.")
    }

    @KafkaListener(
        topics = ["region-weights"]
    )
    fun consumeRegionUpdates(payload: String) {
        val update = mapper.readValue(payload, RegionWeightsUpdate::class.java)
        val currentTime = System.currentTimeMillis()
        var updatedEdges = 0
        var addedEdges = 0

        for (edgeUpdate in update.edges) {
            val edgesFromNode = graphState.adjacencyList.computeIfAbsent(edgeUpdate.fromNodeId) {
                CopyOnWriteArrayList()
            }

            val targetEdge = edgesFromNode.find { it.toNodeId == edgeUpdate.toNodeId && !it.isTransit }

            if (targetEdge != null) {
                targetEdge.timeMillis = edgeUpdate.timeMillis
                targetEdge.lastUpdateTimestamp = currentTime
                updatedEdges++
            } else {
                val newEdge = GlobalEdge(
                    fromNodeId = edgeUpdate.fromNodeId,
                    toNodeId = edgeUpdate.toNodeId,
                    isTransit = false,
                    regionId = update.regionId,
                    timeMillis = edgeUpdate.timeMillis,
                    lastUpdateTimestamp = currentTime
                )
                edgesFromNode.add(newEdge)
                addedEdges++
            }
        }
        log.info("Region ${update.regionId}: updated $updatedEdges existing, added $addedEdges new internal edges")
    }

    @Scheduled(fixedRate = 15_000)
    fun evictDeadRegions() {
        val cutoffTime = System.currentTimeMillis() - METRIC_TTL_MILLIS
        var evictedCount = 0

        for (edges in graphState.adjacencyList.values) {
            for (edge in edges) {
                if (!edge.isTransit && edge.timeMillis != Long.MAX_VALUE) {
                    if (edge.lastUpdateTimestamp < cutoffTime) {
                        edge.timeMillis = Long.MAX_VALUE
                        evictedCount++
                    }
                }
            }
        }

        if (evictedCount > 0) {
            log.warn("TTL ended for $evictedCount internal edges. Routes within this region is closed.")
        }
    }
}