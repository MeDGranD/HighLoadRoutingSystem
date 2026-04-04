package ru.ilyubarskiy.mai.domen

data class BoundingBox(
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean {
        return lat in minLat..maxLat && lon in minLon..maxLon
    }
}