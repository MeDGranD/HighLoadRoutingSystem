package ru.ilyubarskiy.mai.planner_global.service.region

import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.planner_global.domen.graph.BoundingBox

@Service
class RegionLocator {

    private val regionBoxes = mutableMapOf<Int, BoundingBox>()

    fun registerRegion(regionId: Int, bbox: BoundingBox) {
        regionBoxes[regionId] = bbox
    }

    fun findRegion(lat: Double, lon: Double): Int {
        for ((regionId, bbox) in regionBoxes) {
            if (lat >= bbox.minLat && lat <= bbox.maxLat &&
                lon >= bbox.minLon && lon <= bbox.maxLon) {
                return regionId
            }
        }
        throw IllegalArgumentException("Point ($lat, $lon) lays out of known boundary")
    }

    fun getBboxByRegionId(id: Int): BoundingBox = regionBoxes[id] ?: throw IllegalArgumentException("No such region with id: $id")
}