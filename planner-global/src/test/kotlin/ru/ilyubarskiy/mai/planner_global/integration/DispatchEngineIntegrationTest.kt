package ru.ilyubarskiy.mai.planner_global.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.planner_global.domen.*
import ru.ilyubarskiy.mai.planner_global.domen.graph.BoundingBox
import ru.ilyubarskiy.mai.planner_global.service.overlay.GlobalTopologyBuilder
import ru.ilyubarskiy.mai.planner_global.service.overlay.OverlayGraphUpdater
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import ru.ilyubarskiy.mai.planner_global.service.routing.RouteOrchestratorService
import ru.ilyubarskiy.mai.planner_global.utils.PolylineMerger
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=planner-group-test"
    ]
)
@Testcontainers
class DispatchEngineIntegrationTest {

    companion object {
        @Container
        val kafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))

        @Container
        val redisContainer = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers)
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
        }
    }

    @MockkBean(relaxed = true) lateinit var globalTopologyBuilder: GlobalTopologyBuilder
    @MockkBean(relaxed = true) lateinit var overlayGraphUpdater: OverlayGraphUpdater

    @MockkBean lateinit var routeOrchestrator: RouteOrchestratorService
    @MockkBean lateinit var polylineMerger: PolylineMerger
    @MockkBean lateinit var regionLocator: RegionLocator

    @MockkBean(relaxed = true) lateinit var roverServiceClient: RoverServiceGrpcKt.RoverServiceCoroutineStub
    @MockkBean(relaxed = true) lateinit var missionServiceClient: MissionServiceGrpcKt.MissionServiceCoroutineStub

    @Autowired lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired lateinit var redisTemplate: StringRedisTemplate
    @Autowired lateinit var testOutputListener: TestConfig.KafkaOutputListener

    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        testOutputListener.missionSetEvents.clear()
        testOutputListener.orderRoverEvents.clear()
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushDb()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `should consume OrderCreatedEvent, process it and send MissionSetEvent via Kafka`() = runBlocking {
        val orderId = UUID.randomUUID()
        val roverId = UUID.randomUUID().toString()

        val orderEvent = OrderCreatedKafkaEvent(
            orderId = orderId,
            from = Point(55.0, 37.0),
            to = Point(55.01, 37.01),
            capacity = 5.0
        )
        val payloadJson = mapper.writeValueAsString(orderEvent as OrderKafkaEvent)

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

        kafkaTemplate.send("order-planner-event", orderId.toString(), payloadJson)

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertThat(testOutputListener.missionSetEvents).hasSize(1)
            val missionJson = testOutputListener.missionSetEvents.peek()

            val missionTree = mapper.readTree(missionJson)
            assertThat(missionTree.get("rover_id").asText()).isEqualTo(roverId)
            assertThat(missionTree.get("polyline").asText()).isEqualTo("final_merged_polyline")
            assertThat(missionTree.get("orders").isArray).isTrue()

            assertThat(testOutputListener.orderRoverEvents).hasSize(1)
            val orderStatusJson = testOutputListener.orderRoverEvents.peek() // ИСПРАВЛЕНИЕ
            assertThat(orderStatusJson).contains("IN_PROGRESS")
        }

        val redisErrors = redisTemplate.opsForList().range("errors:${orderId}", 0, -1)
        assertThat(redisErrors).isNullOrEmpty()
    }

    @Test
    fun `should consume OrderCancelEvent, abort mission, and update Kafka`() = runBlocking {
        val orderId = UUID.randomUUID()
        val roverId = UUID.randomUUID().toString()
        val missionId = UUID.randomUUID().toString()

        val cancelEvent = OrderCancelKafkaEvent(orderId = orderId)
        val payloadJson = mapper.writeValueAsString(cancelEvent as OrderKafkaEvent)

        val oldMission = MissionPayload(
            mission_id = missionId, rover_id = roverId,
            capacity = 10.0, total_distance_m = 500.0, estimated_time_sec = 100, polyline = "poly",
            orders = listOf(OrderPayload(orderId.toString(), 500.0, 100)),
            route = listOf(WaypointPayload(1, 55.0, 37.0, WaypointAction.PICKUP, orderId.toString()))
        )

        coEvery { missionServiceClient.getByOrderId(any(), any()) } returns GetByOrderIdResponse.newBuilder()
            .setFound(true)
            .setPayloadJson(mapper.writeValueAsString(oldMission))
            .build()

        kafkaTemplate.send("order-planner-event", orderId.toString(), payloadJson)

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertThat(testOutputListener.missionSetEvents).hasSize(1)

            val cancelMissionJson = testOutputListener.missionSetEvents.peek()
            val tree = mapper.readTree(cancelMissionJson)

            assertThat(tree.get("polyline").asText()).isEmpty()
            assertThat(tree.get("orders").isEmpty).isTrue()
        }
    }

    @Test
    fun `should write error to real Redis if no rovers available`() = runBlocking {
        val orderId = UUID.randomUUID()
        val orderEvent = OrderCreatedKafkaEvent(orderId, Point(55.0, 37.0), Point(55.01, 37.01), 5.0)
        val payloadJson = mapper.writeValueAsString(orderEvent as OrderKafkaEvent)

        every { regionLocator.findRegion(any(), any()) } returns 1
        every { regionLocator.getBboxByRegionId(1) } returns BoundingBox(55.0, 37.0, 56.0, 38.0)

        coEvery { roverServiceClient.getFreeRovers(any(), any()) } returns FreeRoversResponse.getDefaultInstance()

        kafkaTemplate.send("order-planner-event", orderId.toString(), payloadJson)

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            val errors = redisTemplate.opsForList().range("errors:${orderId}", 0, -1)

            assertThat(errors).isNotNull()
            assertThat(errors).isNotEmpty()
            assertThat(errors!!.first()).contains("No available rovers in radius")
        }
    }

    @Test
    fun `should consume OrderUpdateEvent and log error to Redis when battery is too low`() = runBlocking {
        // Arrange
        val orderId = UUID.randomUUID().toString()
        val roverId = UUID.randomUUID().toString()

        val oldMission = MissionPayload(
            mission_id = UUID.randomUUID().toString(), rover_id = roverId,
            capacity = 10.0, total_distance_m = 500.0, estimated_time_sec = 100, polyline = "poly",
            orders = listOf(OrderPayload(orderId, 500.0, 100)),
            route = listOf(WaypointPayload(1, 55.0, 37.0, WaypointAction.PICKUP, orderId))
        )

        coEvery { missionServiceClient.getByOrderId(any<GetByOrderIdRequest>(), any<io.grpc.Metadata>()) } returns GetByOrderIdResponse.newBuilder()
            .setFound(true)
            .setPayloadJson(mapper.writeValueAsString(oldMission))
            .build()

        coEvery { roverServiceClient.getRoverStatus(any<RoverStatusRequest>(), any<io.grpc.Metadata>()) } returns RoverStatusResponse.newBuilder()
            .setRoverId(roverId)
            .setLat(55.0).setLon(37.0)
            .setBatteryLevel(10.0) // Батарея < 15%
            .setModel(RoverModel.newBuilder().setAvgSpeed(5.0).setCapacity(50.0).build())
            .build()

        val updateEvent = OrderUpdateEvent(
            orderId = UUID.fromString(orderId),
            from = Point(55.001, 37.001),
            to = Point(55.002, 37.002)
        )
        val payloadJson = mapper.writeValueAsString(updateEvent as OrderKafkaEvent)

        kafkaTemplate.send("order-planner-event", orderId, payloadJson)

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            val errors = redisTemplate.opsForList().range("errors:${orderId}", 0, -1)

            assertThat(errors).isNotNull()
            assertThat(errors).isNotEmpty()
            assertThat(errors!!.first()).contains("battery level is too low")
        }

        coVerify(exactly = 0) { routeOrchestrator.buildRoute(any(), any(), any(), any(), any()) }
    }

    @TestConfiguration
    @EnableKafka
    class TestConfig {
        @Bean fun orderPlannerTopic() = NewTopic("order-planner-event", 1, 1.toShort())
        @Bean fun missionSetTopic() = NewTopic("mission-set-event", 1, 1.toShort())
        @Bean fun orderRoverTopic() = NewTopic("order-rover-event", 1, 1.toShort())
        @Bean fun roverMissionTopic() = NewTopic("rover-mission-event", 1, 1.toShort())
        @Bean fun missionEventTopic() = NewTopic("mission-event", 1, 1.toShort())

        @Bean
        fun testOutputListener(): KafkaOutputListener {
            return KafkaOutputListener()
        }

        class KafkaOutputListener {
            val missionSetEvents = LinkedBlockingQueue<String>()
            val orderRoverEvents = LinkedBlockingQueue<String>()

            @KafkaListener(topics = ["mission-set-event"], groupId = "test-spy-group")
            fun listenMissionSet(payload: String) {
                missionSetEvents.add(payload)
            }

            @KafkaListener(topics = ["order-rover-event"], groupId = "test-spy-group")
            fun listenOrderRover(payload: String) {
                orderRoverEvents.add(payload)
            }
        }
    }
}