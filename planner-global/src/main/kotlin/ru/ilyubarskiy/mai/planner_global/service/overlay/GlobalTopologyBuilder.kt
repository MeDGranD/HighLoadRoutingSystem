package ru.ilyubarskiy.mai.planner_global.service.overlay

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.minio.GetObjectArgs
import io.minio.MinioClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.planner_global.domen.graph.GraphFile
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import ru.ilyubarskiy.mai.planner_global.utils.calculateDistance
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val logger = LoggerFactory.getLogger(GlobalTopologyBuilder::class.java)

@Service
class GlobalTopologyBuilder(
    private val minioClient: MinioClient,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val graphState: OverlayGraphState,
    @Value("\${minio.bucket}") private val bucketName: String,
    private val regionLocator: RegionLocator
) {

    @PostConstruct
    fun buildTopology() {
        logger.info("Loading graph from MinIO...")

        val version = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).`object`("version.txt").build())
            .use { it.bufferedReader().readText().trim() }

        val graphStream = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).`object`("v$version/region_graph.json").build())
        val graphFile: GraphFile = graphStream.use { objectMapper.readValue(it) }

        var transitEdgesCount = 0
        var internalEdgesCount = 0

        for ((regionIdStr, regionData) in graphFile.regions) {
            val regionId = regionIdStr.toInt()
            regionLocator.registerRegion(regionId, regionData.bbox)
            val regionBoundaryNodes = mutableSetOf<Long>()

            for ((_, connections) in regionData.connections) {
                for (conn in connections) {
                    val exitNode = GlobalNode(conn.exitNodeId, conn.exitLat, conn.exitLon, regionId)
                    val enterNode = GlobalNode(conn.enterNodeId, conn.enterLat, conn.enterLon, conn.toRegionId)

                    graphState.addNode(exitNode)
                    graphState.addNode(enterNode)

                    regionBoundaryNodes.add(exitNode.id)

                    val distance = calculateDistance(exitNode.lat, exitNode.lon, enterNode.lat, enterNode.lon)
                    val transitTimeMillis = (distance / 3.0 * 1000).toLong()

                    graphState.addEdge(
                        fromNodeId = exitNode.id,
                        edge = GlobalEdge(
                            fromNodeId = exitNode.id,
                            toNodeId = enterNode.id,
                            isTransit = true,
                            regionId = conn.toRegionId,
                            timeMillis = transitTimeMillis,
                            lastUpdateTimestamp = Long.MAX_VALUE
                        )
                    )
                    transitEdgesCount++
                }
            }

            val nodesList = regionBoundaryNodes.toList()
            for (start in nodesList) {
                for (end in nodesList) {
                    if (start == end) continue

                    graphState.addEdge(
                        fromNodeId = start,
                        edge = GlobalEdge(
                            fromNodeId = start,
                            toNodeId = end,
                            isTransit = false,
                            regionId = regionId,
                            timeMillis = Long.MAX_VALUE,
                            lastUpdateTimestamp = 0L
                        )
                    )
                    internalEdgesCount++
                }
            }
        }

        logger.info("Global topology built. Nodes: ${graphState.nodes.size}, transit edges: $transitEdgesCount, internal edges: $internalEdgesCount")
    }
}