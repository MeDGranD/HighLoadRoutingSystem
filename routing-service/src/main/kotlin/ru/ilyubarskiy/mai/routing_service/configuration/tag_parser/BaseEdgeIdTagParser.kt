package ru.ilyubarskiy.mai.routing_service.configuration.tag_parser

import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ev.EdgeIntAccess
import com.graphhopper.routing.ev.IntEncodedValue
import com.graphhopper.routing.util.parsers.TagParser
import com.graphhopper.storage.IntsRef

class BaseEdgeIdTagParser(
    private val baseEdgeIdEnc: IntEncodedValue
): TagParser {

    companion object {
        const val KEY = "base_edge_id"
    }

    override fun handleWayTags(
        edgeId: Int,
        edgeIntAccess: EdgeIntAccess,
        way: ReaderWay,
        relationFlags: IntsRef
    ) {

        val baseId = way.getTag(KEY)

        val value = baseId.toIntOrNull() ?: -1

        baseEdgeIdEnc.setInt(false, edgeId, edgeIntAccess, value)

    }
}