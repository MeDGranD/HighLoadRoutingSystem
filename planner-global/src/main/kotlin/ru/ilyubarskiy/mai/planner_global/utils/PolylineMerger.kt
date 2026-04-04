package ru.ilyubarskiy.mai.planner_global.utils

import com.graphhopper.jackson.ResponsePathDeserializerHelper.decodePolyline
import com.graphhopper.jackson.ResponsePathSerializer.encodePolyline
import com.graphhopper.util.PointList
import org.springframework.stereotype.Component

@Component
class PolylineMerger {

    fun merge(encodedPolylines: List<String>): String {
        if (encodedPolylines.isEmpty()) return ""

        val mergedPoints = PointList(100, false)

        for (encodedSegment in encodedPolylines) {
            if (encodedSegment.isBlank()) continue

            val segmentPoints = decodePolyline(encodedSegment, 100, false, 1e5)

            for (i in 0 until segmentPoints.size()) {
                val lat = segmentPoints.getLat(i)
                val lon = segmentPoints.getLon(i)

                if (mergedPoints.isEmpty) {
                    mergedPoints.add(lat, lon)
                } else {
                    val lastLat = mergedPoints.getLat(mergedPoints.size() - 1)
                    val lastLon = mergedPoints.getLon(mergedPoints.size() - 1)

                    if (lat != lastLat || lon != lastLon) {
                        mergedPoints.add(lat, lon)
                    }
                }
            }
        }

        return encodePolyline(mergedPoints, false, 1e5)
    }
}