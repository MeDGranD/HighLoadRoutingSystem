package ru.ilyubarskiy.mai.routing_service.configuration.tag_parser

import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EdgeIntAccess
import com.graphhopper.routing.util.parsers.TagParser
import com.graphhopper.storage.IntsRef

class RobotAccessParser(
    private val robotEnc: BooleanEncodedValue
) : TagParser {

    companion object {
        const val KEY = "robot_access"
    }

    override fun handleWayTags(
        edgeId: Int,
        edgeAccess: EdgeIntAccess,
        way: ReaderWay,
        relationFlags: IntsRef
    ) {

        val highway = way.getTag("highway")

        val allowed = when (highway) {

            "footway", "pedestrian", "path", "living_street", "residential", "service",
            "track", "cycleway", "unclassified" -> true

            else -> false
        }

        robotEnc.setBool(false, edgeId, edgeAccess, allowed)
    }
}