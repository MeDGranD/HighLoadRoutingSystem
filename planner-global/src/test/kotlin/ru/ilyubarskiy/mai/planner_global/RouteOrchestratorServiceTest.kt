package ru.ilyubarskiy.mai.planner_global

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import ru.ilyubarskiy.mai.planner_global.service.client.GrpcClientManager
import ru.ilyubarskiy.mai.planner_global.service.overlay.OverlayGraphState
import ru.ilyubarskiy.mai.planner_global.service.region.RegionLocator
import ru.ilyubarskiy.mai.planner_global.service.routing.GlobalRoutePlanner
import ru.ilyubarskiy.mai.planner_global.service.routing.RouteOrchestratorService
import ru.ilyubarskiy.mai.planner_global.utils.PolylineMerger
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RouteOrchestratorServiceTest {

    private lateinit var regionLocator: RegionLocator
    private lateinit var grpcClientManager: GrpcClientManager
    private lateinit var globalPlanner: GlobalRoutePlanner
    private lateinit var graphState: OverlayGraphState
    private lateinit var polylineMerger: PolylineMerger
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>

    private lateinit var orchestrator: RouteOrchestratorService

    private lateinit var stubRegion1: RoutingServiceGrpcKt.RoutingServiceCoroutineStub
    private lateinit var stubRegion2: RoutingServiceGrpcKt.RoutingServiceCoroutineStub

    @BeforeEach
    fun setUp() {
        regionLocator = mockk()
        grpcClientManager = mockk()
        globalPlanner = mockk()
        graphState = mockk()
        polylineMerger = mockk()
        redisTemplate = mockk()
        valueOps = mockk()

        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any<String>()) } returns null
        every { valueOps.set(any<String>(), any<String>(), any<Duration>()) } just Runs

        stubRegion1 = mockk()
        stubRegion2 = mockk()

        every { grpcClientManager.getStubForRegion(1) } returns stubRegion1
        every { grpcClientManager.getStubForRegion(2) } returns stubRegion2

        orchestrator = RouteOrchestratorService(
            regionLocator, grpcClientManager, globalPlanner, graphState, polylineMerger, redisTemplate
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `should return cached polyline immediately if exists in Redis`() = runBlocking {
        val startLat = 55.0; val startLon = 37.0
        val endLat = 55.1; val endLon = 37.1
        val roverSpeed = 5.0

        every { regionLocator.findRegion(startLat, startLon) } returns 1
        every { regionLocator.findRegion(endLat, endLon) } returns 2

        coEvery { stubRegion1.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(10L).build()
        coEvery { stubRegion2.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(20L).build()

        val expectedCacheKey = "route:1:10:2:20"
        val cachedPolyline = "cached_polyline_string"

        every { valueOps.get(expectedCacheKey) } returns cachedPolyline

        val result = orchestrator.buildRoute(startLat, startLon, endLat, endLon, roverSpeed)

        assertThat(result).isEqualTo(cachedPolyline)
        verify(exactly = 0) { globalPlanner.findGlobalPathWithVirtualNodes(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `should request direct route when start and end are in the same region`() = runBlocking {
        val startLat = 55.0; val startLon = 37.0
        val endLat = 55.01; val endLon = 37.01

        every { regionLocator.findRegion(startLat, startLon) } returns 1
        every { regionLocator.findRegion(endLat, endLon) } returns 1

        coEvery { stubRegion1.snapToNode(any(), any()) } returns SnapResponse.newBuilder().setNodeId(10L).build()

        val localPolyline = "local_direct_polyline"
        coEvery { stubRegion1.getRoute(any(), any()) } returns RouteResponse.newBuilder().setEncodedPath(localPolyline).build()

        val result = orchestrator.buildRoute(startLat, startLon, endLat, endLon, 5.0)

        assertThat(result).isEqualTo(localPolyline)
        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        verify(exactly = 0) { globalPlanner.findGlobalPathWithVirtualNodes(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `should assemble complex cross-region route properly`(): Unit = runBlocking {
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

        val finalMergedPolyline = "final_merged_polyline_string"
        every { polylineMerger.merge(any<List<String>>()) } returns finalMergedPolyline

        val result = orchestrator.buildRoute(startLat, startLon, endLat, endLon, 5.0)

        assertThat(result).isEqualTo(finalMergedPolyline)

        val cacheKeySlot = slot<String>()
        val polylineSlot = slot<String>()
        val ttlSlot = slot<Duration>()

        verify(exactly = 1) { valueOps.set(capture(cacheKeySlot), capture(polylineSlot), capture(ttlSlot)) }
        assertThat(cacheKeySlot.captured).isEqualTo("route:1:10:2:20")
        assertThat(polylineSlot.captured).isEqualTo(finalMergedPolyline)
    }
}