package ru.ilyubarskiy.mai.order_service.repository.dto.regionGraph

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphFile(
    val regions: Map<String, RegionData>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegionData(
    val regionId: Int,
    val bbox: BoundingBox,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BoundingBox(
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double
)