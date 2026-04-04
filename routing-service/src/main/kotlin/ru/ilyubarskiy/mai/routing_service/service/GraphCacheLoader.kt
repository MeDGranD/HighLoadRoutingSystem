package ru.ilyubarskiy.mai.routing_service.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.minio.GetObjectArgs
import io.minio.MinioClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.routing_service.configuration.properties.GraphProperties
import ru.ilyubarskiy.mai.routing_service.domen.graph.GraphFile
import java.io.File

private val log = LoggerFactory.getLogger(GraphCacheLoader::class.java)

@Service
@EnableConfigurationProperties(GraphProperties::class)
class GraphCacheLoader(
    private val minioClient: MinioClient,
    private val graphProperties: GraphProperties
) {

    data class BoundaryNode(val id: Long, val lat: Double, val lon: Double)

    final var localBoundaryNodes: List<BoundaryNode> = emptyList()
        private set

    @PostConstruct
    fun fetchAndParseGraph() {
        try {
            log.info("Starting graph loading from MinIO for region ${graphProperties.region}")

            val versionStream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(graphProperties.bucket)
                    .`object`("version.txt")
                    .build()
            )

            val version = versionStream.bufferedReader().use { it.readText().trim() }
            log.info("Actual graph version: ${version}")

            val graphKey = "v$version/region_graph.json"
            val graphStream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(graphProperties.bucket)
                    .`object`(graphKey)
                    .build()
            )

            val graphFile: GraphFile = graphStream.use { stream ->
                jacksonObjectMapper().readValue(stream)
            }
            log.info("File $graphKey successfully downloaded and parsed.", )

            extractBoundaryNodesForCurrentRegion(graphFile)

        } catch (e: Exception) {
            log.error("Graph loading error MinIO: ${e.message}", e)
            throw RuntimeException("Cannot initialize region graph", e)
        }
    }

    private fun extractBoundaryNodesForCurrentRegion(graphFile: GraphFile) {
        val uniqueNodes = mutableMapOf<Long, BoundaryNode>()
        val myRegionIdStr = graphProperties.region

        val myRegionData = graphFile.regions[myRegionIdStr]
        if (myRegionData != null) {
            for ((_, connections) in myRegionData.connections) {
                for (conn in connections) {
                    uniqueNodes[conn.exitNodeId] = BoundaryNode(
                        id = conn.exitNodeId,
                        lat = conn.exitLat,
                        lon = conn.exitLon
                    )
                }
            }
        } else {
            log.warn("Cannot find external edges in region_graph.json for region ${graphProperties.region}")
        }

        for ((otherRegionId, otherRegionData) in graphFile.regions) {
            if (otherRegionId == myRegionIdStr) continue

            val connectionsToMe = otherRegionData.connections[myRegionIdStr] ?: emptyList()
            for (conn in connectionsToMe) {
                uniqueNodes[conn.enterNodeId] = BoundaryNode(
                    id = conn.enterNodeId,
                    lat = conn.enterLat,
                    lon = conn.enterLon
                )
            }
        }

        localBoundaryNodes = uniqueNodes.values.toList()
        log.info("Seccessfully parsed ${localBoundaryNodes.size} unique boundary nodes (in + out) for region ${graphProperties.region}")
    }

    fun downloadPbfIfNeeded(): String {

        val version = try {
            minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket("graph")
                    .`object`("version.txt")
                    .build()
            ).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }.toInt()
        } catch (e: Exception) {
            1
        }

        val objName = "region_${graphProperties.region}.osm.pbf"

        val targetDir = File("graph-data")
        if (!targetDir.exists()) targetDir.mkdirs()

        val localFile = File(targetDir, objName)

        if (localFile.exists() && localFile.length() > 0) {
            return localFile.absolutePath
        }

        log.info("Downloading actualized graph $objName from MinIO...")

        minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(graphProperties.bucket)
                .`object`("v${version}/$objName")
                .build()
        ).use { stream ->
            localFile.outputStream().use { stream.copyTo(it) }
        }

        log.info("PBF file successfully downloaded.")

        return localFile.absolutePath
    }

}