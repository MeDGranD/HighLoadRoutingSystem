package ru.ilyubarskiy.mai.routing_service.configuration.wieghting

import com.graphhopper.routing.ev.AverageSlope
import com.graphhopper.routing.ev.MaxSlope
import com.graphhopper.routing.ev.MaxWidth
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState
import org.springframework.stereotype.Component
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.BaseEdgeIdTagParser
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.RobotAccessParser
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.SurfacePenaltyParser
import ru.ilyubarskiy.mai.routing_service.configuration.tag_parser.WidthTagParser
import ru.ilyubarskiy.mai.routing_service.service.PriorityProvider
import ru.ilyubarskiy.mai.routing_service.service.TrafficProvider
import kotlin.math.abs
import kotlin.math.pow

class RobotWeighting(
    encodingManager: EncodingManager,
    private val trafficProvider: TrafficProvider,
    private val priorityProvider: PriorityProvider
): Weighting {

    private val robotAccessEnc = encodingManager.getBooleanEncodedValue(RobotAccessParser.KEY)
    private val surfacePenaltyEnc = encodingManager.getDecimalEncodedValue(SurfacePenaltyParser.KEY)
    private val widthEnc = encodingManager.getDecimalEncodedValue(WidthTagParser.KEY)
    private val maxWidthEnc = encodingManager.getDecimalEncodedValue(MaxWidth.KEY)
    private val avgSlopeEnc = encodingManager.getDecimalEncodedValue(AverageSlope.KEY)
    private val maxSlopeEnc = encodingManager.getDecimalEncodedValue(MaxSlope.KEY)

    override fun calcMinWeightPerDistance(): Double {
        val speedInMs = DEFAULT_ROBOT_SPEED / 3.6
        val absoluteMinMultiplier = 0.4
        return (1.0 / speedInMs) * absoluteMinMultiplier
    }

    override fun calcEdgeWeight(edge: EdgeIteratorState?, reverse: Boolean): Double {
        edge ?: return Double.POSITIVE_INFINITY
        if(!edge.get(robotAccessEnc)) return Double.POSITIVE_INFINITY

        val roadWidth = edge.get(maxWidthEnc).takeIf { it != Double.POSITIVE_INFINITY } ?: edge.get(widthEnc)
        if (!roadWidth.isNaN() && roadWidth < (ROBOT_WIDTH + 0.1)) {
            return Double.POSITIVE_INFINITY
        }

        var incline = edge.get(avgSlopeEnc)
        var slopeFactor = 1.0
        if (!incline.isNaN()) {
            if (reverse) incline = -incline
            slopeFactor = getSlopeFactor(incline)
        }

        val maxSlope = edge.get(maxSlopeEnc)
        if(!maxSlope.isNaN() && maxSlope > MAX_SAFE_INCLINE) {
            slopeFactor *= getSlopeFactor(maxSlope)
        }

        val edgeId = edge.edge

        val distance = edge.distance
        val surfacePenalty = edge.get(surfacePenaltyEnc)
        val traffic = trafficProvider.getTrafficMultiplier(edgeId)
        val priority = priorityProvider.getPriorityMultiplier(edgeId)
        val speedInMs = (DEFAULT_ROBOT_SPEED / 3.6)

        return (distance / speedInMs) * surfacePenalty * slopeFactor * (1.0 + traffic * 0.2) * ((1.0 - priority * 0.1).coerceAtLeast(0.4))
    }

    override fun calcEdgeMillis(edge: EdgeIteratorState, reverse: Boolean): Long {
        val weight = calcEdgeWeight(edge, reverse)
        if (weight == Double.POSITIVE_INFINITY) {
            return -1
        }
        return (weight * 1000).toLong()
    }

    override fun calcTurnWeight(p0: Int, p1: Int, p2: Int): Double = 0.0
    override fun calcTurnMillis(p0: Int, p1: Int, p2: Int): Long = 0
    override fun hasTurnCosts(): Boolean = false
    override fun getName(): String = "robot_weighting"

    private fun getSlopeFactor(avgSlope: Double): Double {
        val s = avgSlope.coerceAtLeast(0.0)
        return when {
            s < 2.0 -> 1.0
            s < 8.0 -> 1.0 + (s * INCLINE_PENALTY / 100.0).pow(2)
            else -> 1.0 + (s * INCLINE_PENALTY / 100.0).pow(3)
        }
    }

    companion object {
        const val DEFAULT_ROBOT_SPEED: Double = 5.0 //Вынести в конфиг
        const val ROBOT_WIDTH = 0.8
        const val MAX_SAFE_INCLINE = 15.0
        const val INCLINE_PENALTY = 10.0
    }
}