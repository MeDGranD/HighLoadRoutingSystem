package ru.ilyubarskiy.mai.routing_service.domen.kafka

data class RegionWeightsUpdate(
    val regionId: Int,
    val timestamp: Long,
    val edges: List<OverlayEdgeUpdate>
)

data class OverlayEdgeUpdate(
    val fromNodeId: Long,
    val toNodeId: Long,
    val timeMillis: Long,
    val distanceMeters: Double
)