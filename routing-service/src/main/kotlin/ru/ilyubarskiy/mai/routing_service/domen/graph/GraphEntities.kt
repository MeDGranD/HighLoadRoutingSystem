package ru.ilyubarskiy.mai.routing_service.domen.graph

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphFile(
    val regions: Map<String, RegionData>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegionData(
    val regionId: Int,
    val connections: Map<String, List<ConnectionData>> = emptyMap()
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