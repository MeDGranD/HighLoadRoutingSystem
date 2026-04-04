package ru.ilyubarskiy.mai.planner_global.domen.graph

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphFile(
    val regions: Map<String, RegionData>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegionData(
    val regionId: Int,
    val bbox: BoundingBox,
    val connections: Map<String, List<ConnectionData>> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BoundingBox(
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConnectionData(
    val exitNodeId: Long,
    val exitLat: Double,
    val exitLon: Double,
    val enterNodeId: Long,
    val enterLat: Double,
    val enterLon: Double,
    val toRegionId: Int
)