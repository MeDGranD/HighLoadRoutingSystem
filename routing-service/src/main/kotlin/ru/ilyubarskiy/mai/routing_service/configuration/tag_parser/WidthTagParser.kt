package ru.ilyubarskiy.mai.routing_service.configuration.tag_parser

import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EdgeIntAccess
import com.graphhopper.routing.util.parsers.TagParser
import com.graphhopper.storage.IntsRef

class WidthTagParser(
    private val widthEnc: DecimalEncodedValue
): TagParser {

    companion object {
        const val KEY = "width"
        const val DEFAULT_WIDTH = 2.5
    }

    override fun handleWayTags(
        edgeId: Int,
        edgeIntAccess: EdgeIntAccess,
        way: ReaderWay,
        relationFlags: IntsRef
    ) {
        val widthStr = way.getTag(KEY)
        val width = widthStr?.toDoubleOrNull()

        if (width != null && width > 0) {
            widthEnc.setDecimal(false, edgeId, edgeIntAccess, width)
        } else {
            widthEnc.setDecimal(false, edgeId, edgeIntAccess, DEFAULT_WIDTH)
        }
    }
}
