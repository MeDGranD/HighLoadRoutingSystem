package ru.ilyubarskiy.mai.planner_global.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.planner_global.utils.PolylineMerger
import ru.ilyubarskiy.mai.planner_global.domen.*
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import ru.ilyubarskiy.mai.planner_global.service.routing.RouteOrchestratorService
import ru.ilyubarskiy.mai.planner_global.utils.calculateDistance
import java.util.*
import kotlin.math.*

private val log = LoggerFactory.getLogger(DispatchEngine::class.java)

@Service
class DispatchEngine(
    private val routeOrchestrator: RouteOrchestratorService,
    private val polylineMerger: PolylineMerger,
    private val roverServiceClient: RoverServiceGrpcKt.RoverServiceCoroutineStub,
    private val missionServiceClient: MissionServiceGrpcKt.MissionServiceCoroutineStub,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val redisTemplate: StringRedisTemplate,
    private val regionLocator: RegionLocator
) {
    private val mapper = jacksonObjectMapper()

    private val SEARCH_RADIUS_METERS = 3000.0
    private val MAX_DETOUR_SEC = 600
    private val AVAREGE_ENEGRY_SUPPLY_PER_METERS = 340.0

    suspend fun assignOrder(order: OrderCreatedKafkaEvent) {
        log.info("Searching rover for order ${order.orderId}")
        try {
            val bbox =
                regionLocator.findRegion(order.from.lat, order.from.lon).let { regionLocator.getBboxByRegionId(it) }
            val reqBbox = BoundingBox.newBuilder()
                .setMaxLat(bbox.maxLat)
                .setMaxLon(bbox.maxLon)
                .setMinLat(bbox.minLat)
                .setMinLon(bbox.minLon)
                .build()

            val roversResponse = roverServiceClient.getFreeRovers(
                FreeRoversRequest.newBuilder().setBbox(reqBbox).build()
            )

            val candidates = roversResponse.roversList
            if (candidates.isEmpty()) {
                throw RouteNotFoundException("No available rovers in radius $SEARCH_RADIUS_METERS m")
            }

            val bestCandidate = coroutineScope {
                candidates.map { rover ->
                    async { evaluateCandidate(rover, order) }
                }.awaitAll()
                    .filter { it.cost != Double.MAX_VALUE }
                    .minByOrNull { it.cost }
            }
                ?: throw RouteNotFoundException("No rover can deliver order ${order.orderId} within SLA")

            if (bestCandidate.isRidePooling && bestCandidate.bestQueue != null) {
                log.info("Rover ${bestCandidate.roverId} takes in road order. Rebuild mission...")

                val missionResp = missionServiceClient.getByRoverId(
                    GetByRoverIdRequest.newBuilder().setRoverId(bestCandidate.roverId).build()
                )
                val currentMission = mapper.readValue(missionResp.payloadJson, MissionPayload::class.java)
                    .let { it.copy(capacity = it.capacity + order.capacity) }

                val orderDeliveryDist = calculateDistance(order.from.lat, order.from.lon, order.to.lat, order.to.lon)
                val orderDeliveryTime =
                    (orderDeliveryDist / candidates.find { it.roverId == bestCandidate.roverId }!!.model.avgSpeed).toInt()

                val newOrderPayload = OrderPayload(order.orderId.toString(), orderDeliveryDist, orderDeliveryTime)
                val updatedOrders = currentMission.orders + newOrderPayload

                rebuildAndSendMission(currentMission, updatedOrders, bestCandidate.bestQueue)

            } else {
                log.info("Rover ${bestCandidate.roverId} is free. Creating new mission...")
                val finalMission = materialiseNewMission(
                    bestCandidate,
                    order,
                    candidates.find { it.roverId == bestCandidate.roverId }!!.model.avgSpeed,
                    order.capacity
                )
                sendMissionToKafka(finalMission, "mission-set-event")
                val event = MissionSetEvent(
                    payload = finalMission
                )
                val roverEvent = RoverStatusEvent(
                    roverId = UUID.fromString(finalMission.rover_id),
                    status = RoverStatus.ON_MISSION
                ).let { mapper.writeValueAsString(it) }
                kafkaTemplate.send("rover-mission-event", finalMission.rover_id, roverEvent)
                sendMissionEventToKafka(event, "mission-event", UUID.fromString(finalMission.mission_id))
            }
        }
        catch (e: Exception){
            handleOrderError(order.orderId.toString(), e)
        }

        val orderEvent = OrderRoverEvent(
            orderId = order.orderId,
            status = OrderStatus.IN_PROGRESS
        ).let { mapper.writeValueAsString(it) }
        kafkaTemplate.send("order-rover-event", order.orderId.toString(), orderEvent)

    }

    suspend fun cancelOrder(orderId: String) {
        try {
            log.info("Canceling order $orderId")

            val missionResponse = missionServiceClient.getByOrderId(
                GetByOrderIdRequest.newBuilder().setOrderId(orderId).build()
            )
            if (!missionResponse.found) {
                log.warn("Order $orderId does not bound with any order.")
                return
            }

            val currentMission = mapper.readValue(missionResponse.payloadJson, MissionPayload::class.java)

            val remainingOrders = currentMission.orders.filter { it.order_id != orderId }
            val remainingWaypoints = currentMission.route.filter { it.order_id != orderId }

            if (remainingOrders.isEmpty()) {
                cancelMission(currentMission.mission_id, currentMission.rover_id)
            } else {
                rebuildAndSendMission(currentMission, remainingOrders, remainingWaypoints)
            }
        } catch (e: Exception) {
            handleOrderError(orderId, e)
        }
    }

    private suspend fun cancelMission(missionId: String, roverId: String) {
        val cancelPayload = MissionPayload(
            mission_id = missionId, rover_id = roverId,
            total_distance_m = 0.0, estimated_time_sec = 0,
            polyline = "", orders = emptyList(),  route = emptyList(),
            capacity = 0.0
        )
        sendMissionToKafka(cancelPayload, "mission-set-event")
        val event = MissionStatusUpdateEvent(
            UUID.fromString(missionId),
            "CANCEL"
        )
        sendMissionEventToKafka(event, "mission-event", UUID.fromString(missionId))
    }

    suspend fun modifyOrder(
        orderId: String,
        newPickupLat: Double, newPickupLon: Double,
        newDropoffLat: Double, newDropoffLon: Double
    ) {
        try {
            log.info("Changing order parameters $orderId")

            val missionResponse = missionServiceClient.getByOrderId(
                GetByOrderIdRequest.newBuilder().setOrderId(orderId).build()
            )
            if (!missionResponse.found) {
                throw RouteNotFoundException("Mission for order $orderId did not founded.")
            }

            val currentMission = mapper.readValue(missionResponse.payloadJson, MissionPayload::class.java)
            val rover = roverServiceClient.getRoverStatus(
                RoverStatusRequest.newBuilder().setRoverId(currentMission.rover_id).build()
            )

            val activeIdx = findActiveWaypointIndex(rover.lat, rover.lon, currentMission.route)
            val pastRoute = currentMission.route.subList(0, activeIdx)
            val futureRoute = currentMission.route.subList(activeIdx, currentMission.route.size)

            val isAlreadyPickedUp = pastRoute.any { it.order_id == orderId && it.action == WaypointAction.PICKUP }

            val cleanFutureQueue = futureRoute.filter { it.order_id != orderId }

            val newDropoff = WaypointPayload(0, newDropoffLat, newDropoffLon, WaypointAction.DROPOFF, orderId)

            var minTrialTime = Int.MAX_VALUE
            var bestFutureQueue: List<WaypointPayload>? = null

            if (isAlreadyPickedUp) {
                for (i in 0..cleanFutureQueue.size) {
                    val trialFuture = cleanFutureQueue.toMutableList()
                    trialFuture.add(i, newDropoff)

                    val trialTime = simulateQueueTime(rover.lat, rover.lon, trialFuture, rover)
                    if (trialTime < minTrialTime) {
                        minTrialTime = trialTime
                        bestFutureQueue = trialFuture
                    }
                }
            } else {
                val newPickup = WaypointPayload(0, newPickupLat, newPickupLon, WaypointAction.PICKUP, orderId)

                for (i in 0..cleanFutureQueue.size) {
                    for (j in i..cleanFutureQueue.size) {
                        val trialFuture = cleanFutureQueue.toMutableList()
                        trialFuture.add(i, newPickup)
                        trialFuture.add(j + 1, newDropoff)

                        val trialTime = simulateQueueTime(rover.lat, rover.lon, trialFuture, rover)
                        if (trialTime < minTrialTime) {
                            minTrialTime = trialTime
                            bestFutureQueue = trialFuture
                        }
                    }
                }
            }

            val currentRemainingTime = simulateQueueTime(rover.lat, rover.lon, futureRoute, rover)
            val detourSec = minTrialTime - currentRemainingTime

            if (detourSec > MAX_DETOUR_SEC) {
                throw SlaViolationException("New coordinates breaks SLA. Cannot modify order")
            }

            val estimatedRemainingDistance = minTrialTime * rover.model.avgSpeed
            if (rover.batteryLevel - (estimatedRemainingDistance / AVAREGE_ENEGRY_SUPPLY_PER_METERS) < 15.0) {
                throw RouteNotFoundException("Cannot change route: battery level is too low (<15%).")
            }

            val fullRebuiltQueue = pastRoute + bestFutureQueue!!

            val newOrderDistance = calculateDistance(newPickupLat, newPickupLon, newDropoffLat, newDropoffLon)
            val updatedOrders = currentMission.orders.map {
                if (it.order_id == orderId) it.copy(
                    distance_m = newOrderDistance,
                    estimated_time_sec = (newOrderDistance / rover.model.avgSpeed).toInt()
                ) else it
            }

            rebuildAndSendMission(currentMission, updatedOrders, fullRebuiltQueue)

        } catch (e: Exception) {
            handleOrderError(orderId, e)
        }
    }

    private fun handleOrderError(orderId: String, exception: Exception) {
        val errorMessage = exception.message ?: "Internal routing system error"
        val errorKey = "errors:$orderId"

        redisTemplate.opsForList().rightPush(errorKey, errorMessage)
    }

    private data class CandidateEvaluation(
        val roverId: String,
        val cost: Double,
        val isRidePooling: Boolean,
        val bestQueue: List<WaypointPayload>? = null
    )

    private suspend fun evaluateCandidate(rover: RoverStatusResponse, order: OrderCreatedKafkaEvent): CandidateEvaluation {
        if (rover.status == RoverStatus.FREE.value) {
            val dist = calculateDistance(rover.lat, rover.lon, order.from.lat, order.from.lon) +
                    calculateDistance(order.from.lat, order.from.lon, order.to.lat, order.to.lon)
            val timeSec = dist / rover.model.avgSpeed

            if(rover.batteryLevel - dist / AVAREGE_ENEGRY_SUPPLY_PER_METERS < 15.0){
                return CandidateEvaluation(rover.roverId, Double.MAX_VALUE, false)
            }

            return CandidateEvaluation(rover.roverId, timeSec, false)

        } else if (rover.status == RoverStatus.ON_MISSION.value) {

            val missionResp = missionServiceClient.getByRoverId(
                GetByRoverIdRequest.newBuilder().setRoverId(rover.roverId).build()
            )
            if (!missionResp.found) return CandidateEvaluation(rover.roverId, Double.MAX_VALUE, true)

            val mission = mapper.readValue(missionResp.payloadJson, MissionPayload::class.java)

            if (mission.capacity + order.capacity >= rover.model.capacity) {
                return CandidateEvaluation(rover.roverId, Double.MAX_VALUE, true)
            }

            val activeIdx = findActiveWaypointIndex(rover.lat, rover.lon, mission.route)
            val pastRoute = mission.route.subList(0, activeIdx)
            val futureRoute = mission.route.subList(activeIdx, mission.route.size)

            val newPickup = WaypointPayload(0, order.from.lat, order.from.lon, WaypointAction.PICKUP, order.orderId.toString())
            val newDropoff = WaypointPayload(0, order.to.lat, order.to.lon, WaypointAction.DROPOFF, order.orderId.toString())

            var minTrialTime = Int.MAX_VALUE
            var bestFutureQueue: List<WaypointPayload>? = null

            for (i in 0..futureRoute.size) {
                for (j in i..futureRoute.size) {
                    val trialFuture = futureRoute.toMutableList()
                    trialFuture.add(i, newPickup)
                    trialFuture.add(j + 1, newDropoff)

                    val trialTime = simulateQueueTime(rover.lat, rover.lon, trialFuture, rover)

                    if (trialTime < minTrialTime) {
                        minTrialTime = trialTime
                        bestFutureQueue = trialFuture
                    }
                }
            }

            val estimatedRemainingDistance = minTrialTime * rover.model.avgSpeed
            if (rover.batteryLevel - (estimatedRemainingDistance / AVAREGE_ENEGRY_SUPPLY_PER_METERS) < 15.0) {
                return CandidateEvaluation(rover.roverId, Double.MAX_VALUE, true)
            }

            val currentRemainingTime = simulateQueueTime(rover.lat, rover.lon, futureRoute, rover)
            val detourTimeSec = minTrialTime - currentRemainingTime

            if (detourTimeSec <= MAX_DETOUR_SEC) {
                val fullRebuiltQueue = pastRoute + bestFutureQueue!!

                return CandidateEvaluation(
                    roverId = rover.roverId,
                    cost = detourTimeSec * 1.2,
                    isRidePooling = true,
                    bestQueue = fullRebuiltQueue
                )
            }
        }
        return CandidateEvaluation(rover.roverId, Double.MAX_VALUE, true)
    }

    private fun simulateQueueTime(startLat: Double, startLon: Double, queue: List<WaypointPayload>, rover: RoverStatusResponse): Int {
        var currLat = startLat
        var currLon = startLon
        var accTime = 0

        for (wp in queue) {
            accTime += (calculateDistance(currLat, currLon, wp.lat, wp.lon) / rover.model.avgSpeed).toInt()
            currLat = wp.lat
            currLon = wp.lon
        }
        return accTime
    }

    private suspend fun materialiseNewMission(eval: CandidateEvaluation, order: OrderCreatedKafkaEvent, candidateSpeed: Double, newCapacity: Double): MissionPayload = coroutineScope {
        val rover = roverServiceClient.getRoverStatus(
            RoverStatusRequest.newBuilder().setRoverId(eval.roverId).build()
        )

        val appDef = async { routeOrchestrator.buildRoute(rover.lat, rover.lon, order.from.lat, order.from.lon, rover.model.avgSpeed) }
        val delDef = async { routeOrchestrator.buildRoute(order.from.lat, order.from.lon, order.to.lat, order.to.lon, rover.model.avgSpeed) }

        val globalPolyline = polylineMerger.merge(listOf(appDef.await(), delDef.await()))

        val waypoints = listOf(
            WaypointPayload(1, order.from.lat, order.from.lon, WaypointAction.PICKUP, order.orderId.toString()),
            WaypointPayload(2, order.to.lat, order.to.lon, WaypointAction.DROPOFF, order.orderId.toString())
        )

        val approachDist = calculateDistance(rover.lat, rover.lon, order.from.lat, order.from.lon)
        val deliveryDist = calculateDistance(order.from.lat, order.from.lon, order.to.lat, order.to.lon)
        val totalDist = approachDist + deliveryDist
        val totalTimeSec = (totalDist / candidateSpeed).toInt()

        MissionPayload(
            mission_id = "${UUID.randomUUID()}", rover_id = rover.roverId,
            total_distance_m = totalDist, estimated_time_sec = totalTimeSec,
            polyline = globalPolyline,
            orders = listOf(OrderPayload(order.orderId.toString(), totalDist, totalTimeSec)), route = waypoints,
            capacity = newCapacity
        )
    }

    private suspend fun rebuildAndSendMission(
        oldMission: MissionPayload, orders: List<OrderPayload>, queue: List<WaypointPayload>
    ) = coroutineScope {
        val rover = roverServiceClient.getRoverStatus(
            RoverStatusRequest.newBuilder().setRoverId(oldMission.rover_id).build()
        )

        val activeIdx = findActiveWaypointIndex(rover.lat, rover.lon, queue)
        val futureRoute = queue.subList(activeIdx, queue.size)

        val defSegments = mutableListOf<Deferred<String>>()
        var currLat = rover.lat
        var currLon = rover.lon
        var dist = 0.0

        for (wp in futureRoute) {
            val startLat = currLat
            val startLon = currLon
            val endLat = wp.lat
            val endLon = wp.lon
            defSegments.add(async { routeOrchestrator.buildRoute(startLat, startLon, endLat, endLon, rover.model.avgSpeed) })

            dist += calculateDistance(currLat, currLon, wp.lat, wp.lon)
            currLat = endLat
            currLon = endLon
        }

        val newPolyline = (if (defSegments.isNotEmpty()) polylineMerger.merge(defSegments.awaitAll().onEach { s -> println(s)}) else "").also { println(it) }
        val reorderedWp = queue.mapIndexed { idx, wp -> wp.copy(sequence_id = idx + 1) }

        val updatedMission = oldMission.copy(
            polyline = newPolyline,
            orders = orders,
            route = reorderedWp,
            total_distance_m = dist,
            estimated_time_sec = (dist / rover.model.avgSpeed).toInt()
        )

        sendMissionToKafka(updatedMission, "mission-set-event")
        val event = MissionUpdateEvent(
            UUID.fromString(updatedMission.mission_id),
            updatedMission
        )
        sendMissionEventToKafka(event, "mission-event", UUID.fromString(updatedMission.mission_id))
    }

    private fun findActiveWaypointIndex(roverLat: Double, roverLon: Double, route: List<WaypointPayload>): Int {
        if (route.isEmpty()) return 0
        if (route.size == 1) return 0

        var bestIndex = 0
        var minDeviation = Double.MAX_VALUE

        for (i in 0 until route.size - 1) {
            val wpA = route[i]
            val wpB = route[i + 1]

            val d1 = calculateDistance(wpA.lat, wpA.lon, roverLat, roverLon)
            val d2 = calculateDistance(roverLat, roverLon, wpB.lat, wpB.lon)
            val segmentLen = calculateDistance(wpA.lat, wpA.lon, wpB.lat, wpB.lon)

            val deviation = abs((d1 + d2) - segmentLen)

            if (deviation < minDeviation) {
                minDeviation = deviation
                bestIndex = i + 1
            }
        }

        val distToFirst = calculateDistance(roverLat, roverLon, route[0].lat, route[0].lon)
        if (distToFirst < minDeviation) {
            return 0
        }

        return bestIndex
    }

    private fun sendMissionToKafka(mission: MissionPayload, topic: String) {
        val json = mapper.writeValueAsString(mission)
        kafkaTemplate.send(topic, mission.rover_id, json)
    }

    private fun sendMissionEventToKafka(event: KafkaEventMission, topic: String, missionId: UUID){
        val json = mapper.writeValueAsString(event)
        kafkaTemplate.send(topic, missionId.toString(), json)
    }
}