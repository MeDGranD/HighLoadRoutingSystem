package ru.ilyubarskiy.mai.region

import ru.ilyubarskiy.mai.domen.BoundingBox
import kotlin.math.min

class DynamicGridGenerator {

    fun generateGrid(totalBbox: BoundingBox, stepDegreesLat: Double, stepDegreesLon: Double): Map<Int, BoundingBox> {
        val regions = mutableMapOf<Int, BoundingBox>()
        var regionId = 1
        var currentLat = totalBbox.minLat

        while (currentLat < totalBbox.maxLat) {
            val nextLat = min(currentLat + stepDegreesLat, totalBbox.maxLat)
            var currentLon = totalBbox.minLon
            while (currentLon < totalBbox.maxLon) {
                val nextLon = min(currentLon + stepDegreesLon, totalBbox.maxLon)
                regions[regionId] = BoundingBox(currentLat, nextLat, currentLon, nextLon)
                regionId++
                currentLon = nextLon
            }
            currentLat = nextLat
        }
        println("INFO: Сгенерирована сетка из ${regions.size} регионов (шаг Lat: $stepDegreesLon град., шаг Lon: $stepDegreesLon град.).")
        return regions
    }
}