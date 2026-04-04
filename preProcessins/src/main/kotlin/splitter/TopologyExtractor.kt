package ru.ilyubarskiy.mai.splitter

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.domain.v0_6.Way
import org.openstreetmap.osmosis.core.task.v0_6.Sink
import ru.ilyubarskiy.mai.domen.BoundaryLink
import ru.ilyubarskiy.mai.domen.BoundingBox
import ru.ilyubarskiy.mai.domen.RegionGraph
import ru.ilyubarskiy.mai.domen.RegionMetadata

class TopologyExtractor(
    private val regions: Map<Int, BoundingBox>
) : Sink {

    private val nodeRegionMap = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private val nodeLatMap = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.NaN) }
    private val nodeLonMap = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.NaN) }

    val regionGraph = RegionGraph(regions.mapValues { RegionMetadata(it.key, it.value) })

    override fun process(entityContainer: EntityContainer) {
        when (val entity = entityContainer.entity) {
            is Node -> {
                val regionId = findRegion(entity.latitude, entity.longitude)
                if (regionId != -1) {
                    nodeRegionMap.put(entity.id, regionId)

                    nodeLatMap.put(entity.id, entity.latitude)
                    nodeLonMap.put(entity.id, entity.longitude)
                }
            }
            is Way -> {
                if (entity.tags.none { it.key == "highway" }) return
                val wayNodes = entity.wayNodes
                if (wayNodes.size < 2) return

                var prevRegionId = nodeRegionMap.get(wayNodes[0].nodeId)
                var prevNodeId = wayNodes[0].nodeId

                for (i in 1 until wayNodes.size) {
                    val currNodeId = wayNodes[i].nodeId
                    val currRegionId = nodeRegionMap.get(currNodeId)

                    if (prevRegionId != -1 && currRegionId != -1 && prevRegionId != currRegionId) {

                        val prevLat = nodeLatMap.get(prevNodeId)
                        val prevLon = nodeLonMap.get(prevNodeId)
                        val currLat = nodeLatMap.get(currNodeId)
                        val currLon = nodeLonMap.get(currNodeId)

                        addConnection(
                            prevRegionId, currRegionId, entity.id,
                            prevNodeId, prevLat, prevLon,
                            currNodeId, currLat, currLon
                        )
                        addConnection(
                            currRegionId, prevRegionId, entity.id,
                            currNodeId, currLat, currLon,
                            prevNodeId, prevLat, prevLon
                        )
                    }
                    prevRegionId = currRegionId
                    prevNodeId = currNodeId
                }
            }
        }
    }

    private fun addConnection(
        fromRegion: Int, toRegion: Int, wayId: Long,
        exitNode: Long, exitLat: Double, exitLon: Double,
        enterNode: Long, enterLat: Double, enterLon: Double
    ) {
        val metadata = regionGraph.regions[fromRegion]!!
        val links = metadata.connections.computeIfAbsent(toRegion) { mutableListOf() }
        links.add(BoundaryLink(wayId, exitNode, exitLat, exitLon, enterNode, enterLat, enterLon, toRegion))
    }

    private fun findRegion(lat: Double, lon: Double): Int {
        for ((id, bbox) in regions) {
            if (bbox.contains(lat, lon)) return id
        }
        return -1
    }

    fun countTotalLinks() = regionGraph.regions.values.sumOf { it.connections.values.flatten().size }

    override fun initialize(metaData: Map<String, Any>?) {}
    override fun complete() {}
    override fun close() {}
}