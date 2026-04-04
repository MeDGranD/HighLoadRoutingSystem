package ru.ilyubarskiy.mai.routing_service.configuration.tag_parser

import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EdgeIntAccess
import com.graphhopper.routing.util.parsers.TagParser
import com.graphhopper.storage.IntsRef

class SurfacePenaltyParser(
    private val surfaceEnc: DecimalEncodedValue
) : TagParser {

    companion object {
        const val KEY = "surface_penalty"
    }

    override fun handleWayTags(
        edgeId: Int,
        edgeAccess: EdgeIntAccess,
        way: ReaderWay,
        relationFlags: IntsRef
    ) {

        val surface = way.getTag("surface")

        val penalty = when(surface) {

            "asphalt", "paved" -> 1.0
            "gravel" -> 1.3
            "ground" -> 1.2

            else -> 1.1
        }

        surfaceEnc.setDecimal(false, edgeId, edgeAccess, penalty)
    }
}