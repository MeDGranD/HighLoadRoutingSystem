package ru.ilyubarskiy.mai.planner_global.domen.overlay

data class GlobalNode(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val regionId: Int
)

data class GlobalEdge(
    val fromNodeId: Long,
    val toNodeId: Long,
    val isTransit: Boolean,
    val regionId: Int,
    @Volatile var timeMillis: Long,
    @Volatile var lastUpdateTimestamp: Long = 0L
)