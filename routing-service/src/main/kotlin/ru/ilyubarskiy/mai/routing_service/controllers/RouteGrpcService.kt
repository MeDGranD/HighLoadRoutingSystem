package ru.ilyubarskiy.mai.routing_service.controllers

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.jackson.ResponsePathSerializer.encodePolyline
import com.graphhopper.routing.DijkstraOneToMany
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.util.PMap
import com.graphhopper.util.shapes.GHPoint
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.grpc.server.service.GrpcService
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.routing_service.utils.Point
import java.util.PriorityQueue
import kotlin.time.measureTime

private val logger = LoggerFactory.getLogger(RouteGrpcService::class.java)

@GrpcService
class RouteGrpcService(
    private val graphhopper: GraphHopper
): RoutingServiceGrpcKt.RoutingServiceCoroutineImplBase() {

    override suspend fun getRoute(request: RouteRequest): RouteResponse {

        if (!request.hasFromNode() || !request.hasToNode()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Need to set start and end nodes: (from_node, to_node)"))
        }

        val from = request.fromNode
        val to = request.toNode

        val req = GHRequest(from.lat, from.lon, to.lat, to.lon).apply {
            profile = "robot_profile"
            hints.putObject("instructions", false)
        }

        val response = graphhopper.route(req)

        if (response.hasErrors()) {
            throw StatusException(Status.NOT_FOUND.withDescription("Path does not founded: ${response.errors.first().message}"))
        }

        val best = response.best
        val polylineString = encodePolyline(best.points, false, 1e5)

        return RouteResponse.newBuilder()
            .setDistance(best.distance)
            .setTime(best.time)
            .setToNode(to)
            .setEncodedPath(polylineString)
            .build()
    }

    override suspend fun getBoundaryConnections(request: BoundaryConnectionsRequest): BoundaryConnectionsResponse {

        logger.info("Configurating search")
        val point = request.point
        val isStart = request.isStart

        val locationIndex = graphhopper.locationIndex
        val boundaryNodeIds = request.boundariesList.mapNotNull { boundary ->
            val qr = locationIndex.findClosest(boundary.lat, boundary.lon, EdgeFilter.ALL_EDGES)
            if (qr.isValid) boundary.id to qr.closestNode else null
        }.toMap()


        val startQr = locationIndex.findClosest(point.lat, point.lon, EdgeFilter.ALL_EDGES)
        if (!startQr.isValid) return BoundaryConnectionsResponse.getDefaultInstance()
        val sourceNode = startQr.closestNode

        val profile = graphhopper.getProfile("robot_profile")
        val weighting = graphhopper.createWeighting(profile, PMap())
        val explorer = graphhopper.baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES)

        val targetNodesLeft = boundaryNodeIds.values.toMutableSet()
        val results = mutableMapOf<Long, Long>()

        val bestWeights = FloatArray(graphhopper.baseGraph.nodes) { Float.POSITIVE_INFINITY }
        bestWeights[sourceNode] = 0f

        val pq = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        pq.add(0f to sourceNode)
        logger.info("Starting search")
        val timeTaken = measureTime {
            while (pq.isNotEmpty() && targetNodesLeft.isNotEmpty()) {
                val (currentWeight, currentNode) = pq.poll()

                if (currentWeight > bestWeights[currentNode]) continue

                if (targetNodesLeft.contains(currentNode)) {
                    targetNodesLeft.remove(currentNode)
                    val boundaryId = boundaryNodeIds.entries.first { it.value == currentNode }.key

                    results[boundaryId] = currentWeight.toLong()
                }

                val iter = explorer.setBaseNode(currentNode)
                while (iter.next()) {
                    val adjNode = iter.adjNode

                    val edgeWeight = if (isStart) {
                        weighting.calcEdgeWeight(iter, false)
                    } else {
                        weighting.calcEdgeWeight(iter, true)
                    }

                    val newWeight = currentWeight + edgeWeight

                    if (newWeight < bestWeights[adjNode]) {
                        bestWeights[adjNode] = newWeight.toFloat()
                        pq.add(newWeight.toFloat() to adjNode)
                    }
                }
            }
        }

        logger.info("One-to-Many evaluation lasted for: $timeTaken")
        logger.info("Found paths: ${results.size} from ${boundaryNodeIds.size}")

        return BoundaryConnectionsResponse.newBuilder()
            .putAllConnections(results)
            .build()
    }

    override suspend fun snapToNode(request: SnapRequest): SnapResponse {
        val lat = request.lat
        val lon = request.lon

        val qr = graphhopper.locationIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES)

        if (!qr.isValid) {
            throw StatusException(
                Status.NOT_FOUND.withDescription("Cannot snap coordinates ($lat, $lon) to path graph of region.")
            )
        }

        val nodeId = qr.closestNode.toLong()

        return SnapResponse.newBuilder()
            .setNodeId(nodeId)
            .build()
    }

}