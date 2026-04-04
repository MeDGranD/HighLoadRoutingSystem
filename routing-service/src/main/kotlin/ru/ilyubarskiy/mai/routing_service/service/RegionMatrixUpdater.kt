package ru.ilyubarskiy.mai.routing_service.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeExplorer
import com.graphhopper.util.PMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.SimpleDirectedWeightedGraph
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.routing_service.configuration.wieghting.RobotWeighting
import ru.ilyubarskiy.mai.routing_service.domen.kafka.OverlayEdgeUpdate
import ru.ilyubarskiy.mai.routing_service.domen.kafka.RegionWeightsUpdate
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

private val logger = LoggerFactory.getLogger(RegionMatrixUpdater::class.java)

@Service
class RegionMatrixUpdater(
    private val graphhopper: GraphHopper,
    private val partitioner: VirtualGridPartitioner,
    private val kafkaTemplate: KafkaTemplate<String, RegionWeightsUpdate>,
    private val trafficProvider: TrafficProvider,
    private val priorityProvider: PriorityProvider,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${graph.region}") private val regionId: Int,
    @Value("\${app.kafka.weights-topic}") private val topicName: String
) {
    private val cpuDispatcher = Dispatchers.Default

    @Scheduled(fixedRate = 300000, initialDelay = 15000)
    @SchedulerLock(
        name = "matrix_update_region_\${graph.region}",
        lockAtMostFor = "2m",
        lockAtLeastFor = "1m"
    )
    fun updateRegionMatrices() = runBlocking {
        logger.info("Starting matrix update for region $regionId...")
        val startTime = System.currentTimeMillis()

        val baseGraph = graphhopper.baseGraph

        val weighting = RobotWeighting(
            encodingManager = graphhopper.encodingManager,
            trafficProvider = trafficProvider,
            priorityProvider = priorityProvider
        )

        val localOverlayEdges = ConcurrentLinkedQueue<OverlayEdgeUpdate>()

        partitioner.cells.entries.map { (cellId, nodesInCell) ->
            async(cpuDispatcher) {
                val cellInternalBoundaries = nodesInCell.intersect(partitioner.internalBoundaries)
                val cellExternalBoundaries = nodesInCell.intersect(partitioner.externalBoundaries)
                val allCellBoundaries = cellInternalBoundaries + cellExternalBoundaries

                if (allCellBoundaries.isEmpty()) return@async

                val explorer = baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES)

                val bestWeights = DoubleArray(baseGraph.nodes) { Double.POSITIVE_INFINITY }
                val bestDistances = DoubleArray(baseGraph.nodes) { Double.POSITIVE_INFINITY }
                val bestTimesMillis = LongArray(baseGraph.nodes) { Long.MAX_VALUE }

                val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })

                for (startNode in allCellBoundaries) {
                    val edges = calculateOneToManyWithinCell(
                        startNode = startNode,
                        targetNodes = allCellBoundaries,
                        cellId = cellId,
                        weighting = weighting,
                        explorer = explorer,
                        bestWeights = bestWeights,
                        bestDistances = bestDistances,
                        bestTimesMillis = bestTimesMillis,
                        pq = pq
                    )
                    localOverlayEdges.addAll(edges)
                }
            }
        }.awaitAll()

        val calcTime = System.currentTimeMillis() - startTime
        logger.info("Cells evaluated for $calcTime ms. Founded ${localOverlayEdges.size} local paths.")

        val bridgeStartTime = System.currentTimeMillis()
        val explorer = baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES)
        var bridgeCount = 0

        for (nodeId in partitioner.internalBoundaries) {
            val cellId = partitioner.nodeToCell[nodeId]
            val iter = explorer.setBaseNode(nodeId)

            while (iter.next()) {
                val adjNode = iter.adjNode
                if (partitioner.nodeToCell[adjNode] != cellId) {
                    val edgeWeight = weighting.calcEdgeWeight(iter, false)

                    if (edgeWeight != Double.POSITIVE_INFINITY) {
                        localOverlayEdges.add(
                            OverlayEdgeUpdate(
                                fromNodeId = nodeId.toLong(),
                                toNodeId = adjNode.toLong(),
                                timeMillis = weighting.calcEdgeMillis(iter, false),
                                distanceMeters = iter.distance
                            )
                        )
                        bridgeCount++
                    }
                }
            }
        }
        logger.info("Added $bridgeCount connecting edges within cells for ${System.currentTimeMillis() - bridgeStartTime} ms.")

        stitchAndPublish(localOverlayEdges.toList())
    }

    private fun calculateOneToManyWithinCell(
        startNode: Int,
        targetNodes: Set<Int>,
        cellId: Int,
        weighting: RobotWeighting,
        explorer: EdgeExplorer,
        bestWeights: DoubleArray,
        bestDistances: DoubleArray,
        bestTimesMillis: LongArray,
        pq: PriorityQueue<Pair<Double, Int>>
    ): List<OverlayEdgeUpdate> {

        val results = mutableListOf<OverlayEdgeUpdate>()
        val targetsLeft = targetNodes.toMutableSet()
        targetsLeft.remove(startNode)

        pq.clear()
        bestWeights.fill(Double.POSITIVE_INFINITY)
        bestDistances.fill(Double.POSITIVE_INFINITY)
        bestTimesMillis.fill(Long.MAX_VALUE)

        bestWeights[startNode] = 0.0
        bestDistances[startNode] = 0.0
        bestTimesMillis[startNode] = 0L
        pq.add(0.0 to startNode)

        while (pq.isNotEmpty() && targetsLeft.isNotEmpty()) {
            val (currentWeight, currentNode) = pq.poll()

            if (currentWeight > bestWeights[currentNode]) continue

            if (targetsLeft.contains(currentNode)) {
                targetsLeft.remove(currentNode)
                results.add(
                    OverlayEdgeUpdate(
                        fromNodeId = startNode.toLong(),
                        toNodeId = currentNode.toLong(),
                        timeMillis = bestTimesMillis[currentNode],
                        distanceMeters = bestDistances[currentNode]
                    )
                )
            }

            val iter = explorer.setBaseNode(currentNode)
            while (iter.next()) {
                val adjNode = iter.adjNode

                if (partitioner.nodeToCell[adjNode] != cellId) continue

                val edgeWeight = weighting.calcEdgeWeight(iter, false)

                if (edgeWeight == Double.POSITIVE_INFINITY) continue

                val edgeDistance = iter.distance
                val edgeTimeMillis = weighting.calcEdgeMillis(iter, false)

                val newWeight = currentWeight + edgeWeight

                if (newWeight < bestWeights[adjNode]) {
                    bestWeights[adjNode] = newWeight
                    bestDistances[adjNode] = bestDistances[currentNode] + edgeDistance
                    bestTimesMillis[adjNode] = bestTimesMillis[currentNode] + edgeTimeMillis
                    pq.add(newWeight to adjNode)
                }
            }
        }
        return results
    }

    private fun stitchAndPublish(localOverlayEdges: List<OverlayEdgeUpdate>) {
        val stitchStartTime = System.currentTimeMillis()

        val regionGraph = SimpleDirectedWeightedGraph<Int, RoutingEdge>(RoutingEdge::class.java)

        for (edge in localOverlayEdges) {
            val from = edge.fromNodeId.toInt()
            val to = edge.toNodeId.toInt()

            regionGraph.addVertex(from)
            regionGraph.addVertex(to)

            val routingEdge = RoutingEdge(edge.distanceMeters, edge.timeMillis)
            if (regionGraph.addEdge(from, to, routingEdge)) {
                regionGraph.setEdgeWeight(routingEdge, edge.timeMillis.toDouble())
            }
        }

        val finalUpdates = mutableListOf<OverlayEdgeUpdate>()
        val dijkstraAlg = DijkstraShortestPath(regionGraph)

        for (extStart in partitioner.externalBoundaries) {
            if (!regionGraph.containsVertex(extStart)) continue
            val paths = dijkstraAlg.getPaths(extStart)

            for (extEnd in partitioner.externalBoundaries) {
                if (extStart == extEnd || !regionGraph.containsVertex(extEnd)) continue

                val path = paths.getPath(extEnd)
                if (path != null) {
                    val totalDistance = path.edgeList.sumOf { it.distanceMeters }
                    val totalTimeMillis = path.edgeList.sumOf { it.timeMillis }

                    val osmStartId = partitioner.internalToOsmBoundary[extStart]!!.id
                    val osmEndId = partitioner.internalToOsmBoundary[extEnd]!!.id

                    finalUpdates.add(
                        OverlayEdgeUpdate(
                            fromNodeId = osmStartId,
                            toNodeId = osmEndId,
                            timeMillis = totalTimeMillis,
                            distanceMeters = totalDistance
                        )
                    )
                }
            }
        }

        val payload = RegionWeightsUpdate(
            regionId = regionId,
            timestamp = System.currentTimeMillis(),
            edges = finalUpdates
        )
        val cache = jacksonObjectMapper().writeValueAsString(payload)
        redisTemplate.opsForValue().set("region_weights:${regionId}", cache)
        kafkaTemplate.send(topicName, regionId.toString(), payload)

        val stitchTime = System.currentTimeMillis() - stitchStartTime
        logger.info("Reconnection for region is done for $stitchTime ms. Sending ${finalUpdates.size} paths.")
    }
}

class RoutingEdge(
    val distanceMeters: Double,
    val timeMillis: Long
) : DefaultWeightedEdge()