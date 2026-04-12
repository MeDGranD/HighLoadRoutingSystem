package ru.ilyubarskiy.mai.planner_global

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.planner_global.domen.*
import ru.ilyubarskiy.mai.planner_global.domen.graph.BoundingBox
import ru.ilyubarskiy.mai.planner_global.service.DispatchEngine
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import ru.ilyubarskiy.mai.planner_global.service.routing.RouteOrchestratorService
import ru.ilyubarskiy.mai.planner_global.utils.PolylineMerger
import java.util.UUID

class DispatchEngineTest {

    private lateinit var routeOrchestrator: RouteOrchestratorService
    private lateinit var polylineMerger: PolylineMerger
    private lateinit var roverServiceClient: RoverServiceGrpcKt.RoverServiceCoroutineStub
    private lateinit var missionServiceClient: MissionServiceGrpcKt.MissionServiceCoroutineStub
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var regionLocator: RegionLocator
    private lateinit var listOps: ListOperations<String, String>

    private lateinit var dispatchEngine: DispatchEngine
    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        routeOrchestrator = mockk()
        polylineMerger = mockk()
        roverServiceClient = mockk()
        missionServiceClient = mockk()
        kafkaTemplate = mockk(relaxed = true)
        redisTemplate = mockk()
        regionLocator = mockk()
        listOps = mockk(relaxed = true)

        every { redisTemplate.opsForList() } returns listOps

        dispatchEngine = DispatchEngine(
            routeOrchestrator, polylineMerger, roverServiceClient,
            missionServiceClient, kafkaTemplate, redisTemplate, regionLocator
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `assignOrder should log error to Redis when no rovers available`() = runBlocking {
        val order = createDummyOrder()

        every { regionLocator.findRegion(any(), any()) } returns 1
        every { regionLocator.getBboxByRegionId(1) } returns BoundingBox(55.0, 37.0, 56.0, 38.0)

        coEvery { roverServiceClient.getFreeRovers(any(), any()) } returns FreeRoversResponse.getDefaultInstance()

        dispatchEngine.assignOrder(order)

        val errorKeySlot = slot<String>()
        val errorMsgSlot = slot<String>()
        verify(exactly = 1) { listOps.rightPush(capture(errorKeySlot), capture(errorMsgSlot)) }
        Assertions.assertThat(errorKeySlot.captured).isEqualTo("errors:${order.orderId}")
        Assertions.assertThat(errorMsgSlot.captured).contains("No available rovers in radius")

        verify(exactly = 1) { kafkaTemplate.send("order-rover-event", order.orderId.toString(), any()) }
    }

    @Test
    fun `assignOrder should create new mission when free rover is selected`() = runBlocking {
        val order = createDummyOrder()
        val roverId = UUID.randomUUID().toString()

        every { regionLocator.findRegion(any(), any()) } returns 1
        every { regionLocator.getBboxByRegionId(1) } returns BoundingBox(55.0, 37.0, 56.0, 38.0)

        val rover = RoverStatusResponse.newBuilder()
            .setRoverId(roverId)
            .setStatus(RoverStatus.FREE.value)
            .setLat(55.0).setLon(37.0)
            .setBatteryLevel(100.0)
            .setModel(RoverModel.newBuilder().setAvgSpeed(5.0).setCapacity(50.0).build())
            .build()

        coEvery { roverServiceClient.getFreeRovers(any(), any()) } returns FreeRoversResponse.newBuilder().addRovers(rover).build()
        coEvery { roverServiceClient.getRoverStatus(any(), any()) } returns rover

        coEvery { routeOrchestrator.buildRoute(any(), any(), any(), any(), any()) } returns "route_segment"
        every { polylineMerger.merge(any()) } returns "final_merged_polyline"

        dispatchEngine.assignOrder(order)

        coVerify(exactly = 2) { routeOrchestrator.buildRoute(any(), any(), any(), any(), any()) }

        val topicSlot = mutableListOf<String>()
        verify(exactly = 4) { kafkaTemplate.send(capture(topicSlot), any(), any()) }

        Assertions.assertThat(topicSlot).contains(
            "mission-set-event",
            "rover-mission-event",
            "mission-event",
            "order-rover-event"
        )

        verify(exactly = 0) { listOps.rightPush(any(), any()) }
    }

    @Test
    fun `cancelOrder should abort silently if mission not found`() = runBlocking {
        val orderId = "order-123"
        coEvery { missionServiceClient.getByOrderId(any(), any()) } returns GetByOrderIdResponse.newBuilder().setFound(false).build()

        dispatchEngine.cancelOrder(orderId)

        verify(exactly = 0) { kafkaTemplate.send(any(), any(), any()) }
        verify(exactly = 0) { listOps.rightPush(any(), any()) }
    }

    @Test
    fun `cancelOrder should send cancel mission payload if it was the last order`() = runBlocking {
        val orderId = "order-123"
        val roverId = UUID.randomUUID().toString()
        val missionId = UUID.randomUUID().toString()

        val oldMission = MissionPayload(
            mission_id = missionId, rover_id = roverId,
            capacity = 10.0, total_distance_m = 500.0, estimated_time_sec = 100, polyline = "poly",
            orders = listOf(OrderPayload(orderId, 500.0, 100)),
            route = listOf(WaypointPayload(1, 55.0, 37.0, WaypointAction.PICKUP, orderId))
        )

        coEvery { missionServiceClient.getByOrderId(any(), any()) } returns GetByOrderIdResponse.newBuilder()
            .setFound(true)
            .setPayloadJson(mapper.writeValueAsString(oldMission))
            .build()

        dispatchEngine.cancelOrder(orderId)

        val payloadSlot = slot<String>()
        verify(exactly = 1) { kafkaTemplate.send(eq("mission-set-event"), eq(roverId), capture(payloadSlot)) }

        val sentPayload = mapper.readTree(payloadSlot.captured)
        Assertions.assertThat(sentPayload.get("polyline").asText()).isEmpty()
        Assertions.assertThat(sentPayload.get("orders").isEmpty).isTrue()

        verify(exactly = 1) { kafkaTemplate.send(eq("mission-event"), eq(missionId), match { it.contains("CANCEL") }) }
    }

    @Test
    fun `modifyOrder should throw RouteNotFoundException when battery is too low after calculation`() = runBlocking {
        val orderId = "order-123"
        val roverId = UUID.randomUUID().toString()

        val oldMission = MissionPayload(
            mission_id = UUID.randomUUID().toString(), rover_id = roverId,
            capacity = 10.0, total_distance_m = 500.0, estimated_time_sec = 100, polyline = "poly",
            orders = listOf(OrderPayload(orderId, 500.0, 100)),
            route = listOf(WaypointPayload(1, 55.0, 37.0, WaypointAction.PICKUP, orderId))
        )

        coEvery { missionServiceClient.getByOrderId(any(), any()) } returns GetByOrderIdResponse.newBuilder()
            .setFound(true)
            .setPayloadJson(mapper.writeValueAsString(oldMission))
            .build()

        coEvery { roverServiceClient.getRoverStatus(any(), any()) } returns RoverStatusResponse.newBuilder()
            .setRoverId(roverId)
            .setLat(55.0).setLon(37.0)
            .setBatteryLevel(10.0) // < 15%
            .setModel(RoverModel.newBuilder().setAvgSpeed(5.0).setCapacity(50.0).build())
            .build()

        dispatchEngine.modifyOrder(orderId, 55.001, 37.001, 55.002, 37.002)

        val errorMsgSlot = slot<String>()
        verify(exactly = 1) { listOps.rightPush(eq("errors:$orderId"), capture(errorMsgSlot)) }
        Assertions.assertThat(errorMsgSlot.captured).contains("battery level is too low")

        coVerify(exactly = 0) { routeOrchestrator.buildRoute(any(), any(), any(), any(), any()) }
    }

    private fun createDummyOrder(): OrderCreatedKafkaEvent {
        return OrderCreatedKafkaEvent(
            orderId = UUID.randomUUID(),
            from = Point(55.0, 37.0),
            to = Point(55.1, 37.1),
            capacity = 5.0
        )
    }

}