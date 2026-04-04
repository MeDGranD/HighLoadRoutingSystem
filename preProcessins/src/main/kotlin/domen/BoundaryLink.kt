package ru.ilyubarskiy.mai.domen

data class BoundaryLink(
    val wayId: Long,
    val exitNodeId: Long,
    val exitLat: Double,
    val exitLon: Double,
    val enterNodeId: Long,
    val enterLat: Double,
    val enterLon: Double,
    val toRegionId: Int
)