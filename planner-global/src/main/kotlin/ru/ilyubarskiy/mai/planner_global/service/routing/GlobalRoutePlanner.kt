package ru.ilyubarskiy.mai.planner_global.service.routing

import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.generated.RouteNode
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import ru.ilyubarskiy.mai.planner_global.service.overlay.OverlayGraphState
import ru.ilyubarskiy.mai.planner_global.utils.calculateDistance
import java.util.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Service
class GlobalRoutePlanner(
    private val graphState: OverlayGraphState
) {

    data class AStarNode(
        val nodeId: Long,
        val fScore: Long
    )

    private fun heuristic(node: GlobalNode, target: GlobalNode, roverSpeed: Double): Long {
        val distanceMeters = calculateDistance(node.lat, node.lon, target.lat, target.lon)
        return (distanceMeters / roverSpeed * 1000).toLong()
    }

    fun findGlobalPathWithVirtualNodes(
        startPoint: RouteNode,
        startConnections: Map<Long, Long>,
        endPoint: RouteNode,
        endConnections: Map<Long, Long>,
        roverSpeed: Double
    ): List<GlobalEdge> {

        val V_START_ID = -1L
        val V_END_ID = -2L

        val targetGlobalNode = GlobalNode(V_END_ID, endPoint.lat, endPoint.lon, -1)

        val openSet = PriorityQueue<AStarNode>(compareBy { it.fScore })
        val closedSet = mutableSetOf<Long>()
        val cameFrom = mutableMapOf<Long, Pair<Long, GlobalEdge?>>()
        val gScore = mutableMapOf<Long, Long>().withDefault { Long.MAX_VALUE }

        gScore[V_START_ID] = 0L
        for ((boundaryId, timeFromStart) in startConnections) {
            val boundaryNode = graphState.nodes[boundaryId] ?: continue

            gScore[boundaryId] = timeFromStart
            cameFrom[boundaryId] = Pair(V_START_ID, null)

            val fScore = timeFromStart + heuristic(boundaryNode, targetGlobalNode, roverSpeed)
            openSet.add(AStarNode(boundaryId, fScore))
        }

        while (openSet.isNotEmpty()) {
            val currentId = openSet.poll().nodeId

            if (endConnections.containsKey(currentId)) {
                val pathToLastBoundary = reconstructPath(cameFrom, currentId)
                return pathToLastBoundary
            }

            if (!closedSet.add(currentId)) continue

            val edges = graphState.adjacencyList[currentId] ?: emptyList()

            for (edge in edges) {
                if (edge.timeMillis == Long.MAX_VALUE) continue

                val neighborId = edge.toNodeId
                if (neighborId in closedSet) continue

                val tentativeGScore = gScore.getValue(currentId) + edge.timeMillis

                if (tentativeGScore < gScore.getValue(neighborId)) {
                    cameFrom[neighborId] = Pair(currentId, edge)
                    gScore[neighborId] = tentativeGScore

                    val neighborNode = graphState.nodes[neighborId]!!
                    val fScore = tentativeGScore + heuristic(neighborNode, targetGlobalNode, roverSpeed)
                    openSet.add(AStarNode(neighborId, fScore))
                }
            }
        }

        throw RuntimeException("Global path cannot be founded.")
    }

    private fun reconstructPath(cameFrom: Map<Long, Pair<Long, GlobalEdge?>>, currentId: Long): List<GlobalEdge> {
        val path = mutableListOf<GlobalEdge>()
        var curr = currentId

        while (cameFrom.containsKey(curr)) {
            val (prevNodeId, edge) = cameFrom[curr]!!
            if (edge != null) {
                path.add(edge)
            }
            curr = prevNodeId
            if (curr == -1L) break
        }

        return path.reversed()
    }
}