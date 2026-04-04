package ru.ilyubarskiy.mai.planner_global.service.routing

import kotlinx.coroutines.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.planner_global.utils.PolylineMerger
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import ru.ilyubarskiy.mai.planner_global.service.client.GrpcClientManager
import ru.ilyubarskiy.mai.planner_global.service.overlay.OverlayGraphState
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import java.time.Duration

@Service
class RouteOrchestratorService(
    private val regionLocator: RegionLocator,
    private val grpcClientManager: GrpcClientManager,
    private val globalPlanner: GlobalRoutePlanner,
    private val graphState: OverlayGraphState,
    private val polylineMerger: PolylineMerger,
    private val redisTemplate: StringRedisTemplate
) {

    suspend fun buildRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double, roverSpeed: Double): String = coroutineScope {
        val startRegionId = regionLocator.findRegion(startLat, startLon)
        val endRegionId = regionLocator.findRegion(endLat, endLon)

        val startSnapDeferred = async {
            grpcClientManager.getStubForRegion(startRegionId)
                .snapToNode(SnapRequest.newBuilder().setLat(startLat).setLon(startLon).build())
        }
        val endSnapDeferred = async {
            grpcClientManager.getStubForRegion(endRegionId)
                .snapToNode(SnapRequest.newBuilder().setLat(endLat).setLon(endLon).build())
        }

        val startNodeId = startSnapDeferred.await().nodeId
        val endNodeId = endSnapDeferred.await().nodeId

        val cacheKey = "route:$startRegionId:$startNodeId:$endRegionId:$endNodeId"

        val cachedPolyline = redisTemplate.opsForValue().get(cacheKey)
        if (cachedPolyline != null) {
            return@coroutineScope cachedPolyline
        }

        val startNode = RouteNode.newBuilder().setLat(startLat).setLon(startLon).build()
        val endNode = RouteNode.newBuilder().setLat(endLat).setLon(endLon).build()

        if (startRegionId == endRegionId) {
            val stub = grpcClientManager.getStubForRegion(startRegionId)
            val req = RouteRequest.newBuilder().setFromNode(startNode).setToNode(endNode).build()
            return@coroutineScope stub.getRoute(req).encodedPath
        }

        val startBoundaries = graphState.getBoundariesForRegion(startRegionId)
        val endBoundaries = graphState.getBoundariesForRegion(endRegionId)

        val startConnectionsDeferred = async {
            getBoundaryConnections(startRegionId, startNode, startBoundaries.map { BoundaryNode.newBuilder().setId(it.id).setLat(it.lat).setLon(it.lon).build() }, true)
        }
        val endConnectionsDeferred = async {
            getBoundaryConnections(endRegionId, endNode, endBoundaries.map { BoundaryNode.newBuilder().setId(it.id).setLat(it.lat).setLon(it.lon).build() }, false)
        }

        val startConnections = startConnectionsDeferred.await()
        val endConnections = endConnectionsDeferred.await()

        val globalPathEdges = globalPlanner.findGlobalPathWithVirtualNodes(
            startNode, startConnections, endNode, endConnections, roverSpeed
        )

        val finalPolyLine = assembleGeometry(startNode, endNode, startRegionId, endRegionId, globalPathEdges)
        redisTemplate.opsForValue().set(cacheKey, finalPolyLine, Duration.ofMinutes(5))
        return@coroutineScope finalPolyLine
    }

    private suspend fun assembleGeometry(
        startNode: RouteNode,
        endNode: RouteNode,
        startRegionId: Int,
        endRegionId: Int,
        pathEdges: List<GlobalEdge>
    ): String = coroutineScope {

        val deferredSegments = mutableListOf<Deferred<String>>()

        val firstEdge = pathEdges.first()
        val firstBoundaryNode = graphState.nodes[firstEdge.fromNodeId]!!

        deferredSegments.add(async {
            val req = RouteRequest.newBuilder()
                .setFromNode(startNode)
                .setToNode(firstBoundaryNode.toRouteNode())
                .build()
            grpcClientManager.getStubForRegion(startRegionId).getRoute(req).encodedPath
        })

        for (edge in pathEdges) {
            if (!edge.isTransit) {
                deferredSegments.add(async {
                    val fromGlobalNode = graphState.nodes[edge.fromNodeId]!!
                    val toGlobalNode = graphState.nodes[edge.toNodeId]!!

                    val req = RouteRequest.newBuilder()
                        .setFromNode(fromGlobalNode.toRouteNode())
                        .setToNode(toGlobalNode.toRouteNode())
                        .build()
                    grpcClientManager.getStubForRegion(edge.regionId).getRoute(req).encodedPath
                })
            }
        }

        val lastEdge = pathEdges.last()
        val lastBoundaryNode = graphState.nodes[lastEdge.toNodeId]!!

        deferredSegments.add(async {
            val req = RouteRequest.newBuilder()
                .setFromNode(lastBoundaryNode.toRouteNode())
                .setToNode(endNode)
                .build()
            grpcClientManager.getStubForRegion(endRegionId).getRoute(req).encodedPath
        })

        val segmentsPolylines = deferredSegments.awaitAll()

        return@coroutineScope polylineMerger.merge(segmentsPolylines)
    }

    private fun GlobalNode.toRouteNode(): RouteNode {
        return RouteNode.newBuilder().setLat(this.lat).setLon(this.lon).build()
    }

    private suspend fun getBoundaryConnections(
        regionId: Int, point: RouteNode, boundaries: List<BoundaryNode>, isStart: Boolean
    ): Map<Long, Long> {
        val stub = grpcClientManager.getStubForRegion(regionId)
        val req = BoundaryConnectionsRequest.newBuilder()
            .setPoint(point)
            .addAllBoundaries(boundaries)
            .setIsStart(isStart)
            .build()

        return stub.getBoundaryConnections(req).connectionsMap
    }
}

