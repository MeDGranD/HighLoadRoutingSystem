package ru.ilyubarskiy.mai.routing_service.service

import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger(VirtualGridPartitioner::class.java)

@Component
class VirtualGridPartitioner(
    private val graphhopper: GraphHopper,
    private val graphCacheLoader: GraphCacheLoader
) {
    val cells = ConcurrentHashMap<Int, MutableList<Int>>()
    lateinit var nodeToCell: IntArray
    val internalBoundaries = mutableSetOf<Int>()
    val externalBoundaries = mutableSetOf<Int>()
    val internalToOsmBoundary = mutableMapOf<Int, GraphCacheLoader.BoundaryNode>()

    @PostConstruct
    fun partitionGraph() {
        val baseGraph = graphhopper.baseGraph
        val nodeAccess = baseGraph.nodeAccess
        val explorer = baseGraph.createEdgeExplorer()
        val locationIndex = graphhopper.locationIndex

        val totalNodes = baseGraph.nodes
        nodeToCell = IntArray(totalNodes) { -1 }

        for (boundary in graphCacheLoader.localBoundaryNodes) {
            val qr = locationIndex.findClosest(boundary.lat, boundary.lon, EdgeFilter.ALL_EDGES)
            if (qr.isValid) {
                val internalNodeId = qr.closestNode
                externalBoundaries.add(internalNodeId)
                internalToOsmBoundary[internalNodeId] = boundary
            } else {
                logger.warn("Attention: boundary node OSM ${boundary.id} does not founded in graph!")
            }
        }

        for (nodeId in 0 until totalNodes) {
            val lat = nodeAccess.getLat(nodeId)
            val lon = nodeAccess.getLon(nodeId)

            val cellX = (lon / 0.02).toInt()
            val cellY = (lat / 0.02).toInt()
            val cellId = cellX * 31 + cellY

            nodeToCell[nodeId] = cellId
            cells.computeIfAbsent(cellId) { mutableListOf() }.add(nodeId)
        }

        for (nodeId in 0 until totalNodes) {
            val cellId = nodeToCell[nodeId]
            val iter = explorer.setBaseNode(nodeId)

            while (iter.next()) {
                val adjNode = iter.adjNode
                val adjCellId = nodeToCell[adjNode]

                if (cellId != adjCellId) {
                    internalBoundaries.add(nodeId)
                    internalBoundaries.add(adjNode)
                }
            }
        }

        logger.info("Partitioning completed. Grids count: ${cells.size}, " +
                "Internal nodes: ${internalBoundaries.size}, " +
                "External nodes (from JSON): ${externalBoundaries.size}")
    }
}