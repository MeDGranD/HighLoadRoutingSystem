package ru.ilyubarskiy.mai.planner_global.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import ru.ilyubarskiy.mai.generated.BoundaryConnectionsResponse
import ru.ilyubarskiy.mai.generated.RouteResponse
import ru.ilyubarskiy.mai.generated.RoutingServiceGrpcKt
import ru.ilyubarskiy.mai.generated.SnapResponse
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import ru.ilyubarskiy.mai.planner_global.service.client.GrpcClientManager
import ru.ilyubarskiy.mai.planner_global.service.overlay.GlobalTopologyBuilder
import ru.ilyubarskiy.mai.planner_global.service.overlay.OverlayGraphState
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import ru.ilyubarskiy.mai.planner_global.service.routing.GlobalRoutePlanner
import ru.ilyubarskiy.mai.planner_global.service.routing.RouteOrchestratorService
import ru.ilyubarskiy.mai.planner_global.utils.PolylineMerger
import java.util.concurrent.ConcurrentHashMap

@SpringBootTest(
    properties = [
        "spring.main.allow-bean-definition-overriding=true"
    ]
)
@Testcontainers
class RouteOrchestratorIntegrationTest {

    companion object {
        @Container
        val redisContainer = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
        }
    }

    @MockkBean
    lateinit var regionLocator: RegionLocator
    @MockkBean lateinit var grpcClientManager: GrpcClientManager
    @MockkBean lateinit var globalPlanner: GlobalRoutePlanner
    @MockkBean lateinit var graphState: OverlayGraphState
    @MockkBean lateinit var polylineMerger: PolylineMerger
    @MockkBean(relaxed = true)
    lateinit var globalTopologyBuilder: GlobalTopologyBuilder

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate
    @Autowired lateinit var orchestrator: RouteOrchestratorService

    private lateinit var stubRegion1: RoutingServiceGrpcKt.RoutingServiceCoroutineStub
    private lateinit var stubRegion2: RoutingServiceGrpcKt.RoutingServiceCoroutineStub

    @BeforeEach
    fun setUp() {
        stubRegion1 = mockk()
        stubRegion2 = mockk()

        every { grpcClientManager.getStubForRegion(1) } returns stubRegion1
        every { grpcClientManager.getStubForRegion(2) } returns stubRegion2

        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushDb()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `should return cached polyline immediately from real Redis if exists`() = runBlocking {
        val startLat = 55.0; val startLon = 37.0
        val endLat = 55.1; val endLon = 37.1
        val roverSpeed = 5.0

        every { regionLocator.findRegion(startLat, startLon) } returns 1
        every { regionLocator.findRegion(endLat, endLon) } returns 2

        coEvery { stubRegion1.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(10L).build()
        coEvery { stubRegion2.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(20L).build()

        val expectedCacheKey = "route:1:10:2:20"
        val cachedPolyline = "real_redis_cached_polyline"

        redisTemplate.opsForValue().set(expectedCacheKey, cachedPolyline)

        val result = orchestrator.buildRoute(startLat, startLon, endLat, endLon, roverSpeed)

        assertThat(result).isEqualTo(cachedPolyline)

        coVerify(exactly = 0) { globalPlanner.findGlobalPathWithVirtualNodes(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { stubRegion1.getBoundaryConnections(any(), any()) }
    }

    @Test
    fun `should request direct route when start and end are in the same region`() = runBlocking {
        val startLat = 55.0; val startLon = 37.0
        val endLat = 55.01; val endLon = 37.01

        every { regionLocator.findRegion(startLat, startLon) } returns 1
        every { regionLocator.findRegion(endLat, endLon) } returns 1 // Тот же регион

        coEvery { stubRegion1.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(10L).build()

        val localPolyline = "local_direct_polyline"
        coEvery { stubRegion1.getRoute(any(), any()) } returns RouteResponse.newBuilder().setEncodedPath(localPolyline).build()

        val result = orchestrator.buildRoute(startLat, startLon, endLat, endLon, 5.0)

        assertThat(result).isEqualTo(localPolyline)

        val cacheValue = redisTemplate.opsForValue().get("route:1:10:1:10")
        assertThat(cacheValue).isNull()
    }

    @Test
    fun `should calculate complex cross-region route, merge it, and save to real Redis`(): Unit = runBlocking {
        val startLat = 55.0; val startLon = 37.0
        val endLat = 55.1; val endLon = 37.1

        every { regionLocator.findRegion(startLat, startLon) } returns 1
        every { regionLocator.findRegion(endLat, endLon) } returns 2

        coEvery { stubRegion1.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(10L).build()
        coEvery { stubRegion2.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(20L).build()

        val boundary1 = GlobalNode(100L, 55.05, 37.05, 1)
        val boundary2 = GlobalNode(200L, 55.06, 37.06, 2)
        every { graphState.getBoundariesForRegion(1) } returns listOf(boundary1)
        every { graphState.getBoundariesForRegion(2) } returns listOf(boundary2)

        coEvery { stubRegion1.getBoundaryConnections(any(), any()) } returns BoundaryConnectionsResponse.newBuilder().putConnections(100L, 5000L).build()
        coEvery { stubRegion2.getBoundaryConnections(any(), any()) } returns BoundaryConnectionsResponse.newBuilder().putConnections(200L, 6000L).build()

        val transitEdge = GlobalEdge(fromNodeId = 100L, toNodeId = 200L, isTransit = true, regionId = 1, timeMillis = 15000L)
        every { globalPlanner.findGlobalPathWithVirtualNodes(any(), any(), any(), any(), any()) } returns listOf(transitEdge)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(100L to boundary1, 200L to boundary2))

        val polylineStartToB1 = "poly_start_to_b1"
        val polylineB2ToEnd = "poly_b2_to_end"

        coEvery { stubRegion1.getRoute(any(), any()) } returns RouteResponse.newBuilder().setEncodedPath(polylineStartToB1).build()
        coEvery { stubRegion2.getRoute(any(), any()) } returns RouteResponse.newBuilder().setEncodedPath(polylineB2ToEnd).build()

        val finalMergedPolyline = "final_merged_cross_region_polyline"
        every { polylineMerger.merge(listOf(polylineStartToB1, polylineB2ToEnd)) } returns finalMergedPolyline

        val result = orchestrator.buildRoute(startLat, startLon, endLat, endLon, 5.0)

        assertThat(result).isEqualTo(finalMergedPolyline)

        val expectedCacheKey = "route:1:10:2:20"
        val savedInRedis = redisTemplate.opsForValue().get(expectedCacheKey)

        assertThat(savedInRedis).isNotNull()
        assertThat(savedInRedis).isEqualTo(finalMergedPolyline)

        val ttl = redisTemplate.getExpire(expectedCacheKey)
        assertThat(ttl).isBetween(290L, 300L)
    }
}