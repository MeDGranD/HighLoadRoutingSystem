package ru.ilyubarskiy.mai.routing_service.utils

data class Point(val lat: Double, val lon: Double)

fun encodePolyline(points: List<Point>): String {
    var lastLat = 0
    var lastLon = 0
    val result = StringBuilder()

    for (point in points) {
        val lat = (point.lat * 1e5).toInt()
        val lon = (point.lon * 1e5).toInt()

        val dLat = lat - lastLat
        val dLon = lon - lastLon

        encodeValue(dLat, result)
        encodeValue(dLon, result)

        lastLat = lat
        lastLon = lon
    }

    return result.toString()
}

private fun encodeValue(value: Int, result: StringBuilder) {
    var v = value shl 1
    if (value < 0) {
        v = v.inv()
    }

    while (v >= 0x20) {
        val nextValue = (0x20 or (v and 0x1f)) + 63
        result.append(nextValue.toChar())
        v = v shr 5
    }

    v += 63
    result.append(v.toChar())
}