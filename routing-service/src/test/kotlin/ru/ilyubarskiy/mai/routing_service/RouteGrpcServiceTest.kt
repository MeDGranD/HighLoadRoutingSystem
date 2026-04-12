package ru.ilyubarskiy.mai.routing_service

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.config.Profile
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.EdgeExplorer
import com.graphhopper.util.EdgeIterator
import com.graphhopper.util.PointList
import io.grpc.Status
import io.grpc.StatusException
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import ru.ilyubarskiy.mai.generated.BoundaryConnectionsRequest
import ru.ilyubarskiy.mai.generated.BoundaryNode
import ru.ilyubarskiy.mai.generated.RouteNode
import ru.ilyubarskiy.mai.generated.RouteRequest
import ru.ilyubarskiy.mai.generated.SnapRequest
import ru.ilyubarskiy.mai.routing_service.controllers.RouteGrpcService

@ExtendWith(MockitoExtension::class)
class RouteGrpcServiceTest {

    private lateinit var graphHopper: GraphHopper
    private lateinit var routeGrpcService: RouteGrpcService

    @BeforeEach
    fun setUp() {
        graphHopper = mockk()
        routeGrpcService = RouteGrpcService(graphHopper)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }


    @Test
    fun `getRoute should throw INVALID_ARGUMENT when nodes are missing`(): Unit = runBlocking {

        val request = RouteRequest.newBuilder().build()

        assertThatThrownBy { runBlocking { routeGrpcService.getRoute(request) } }
            .isInstanceOf(StatusException::class.java)
            .hasFieldOrPropertyWithValue("status.code", Status.Code.INVALID_ARGUMENT)
            .hasMessageContaining("Need to set start and end nodes")
    }

    @Test
    fun `getRoute should throw NOT_FOUND when graphhopper returns errors`(): Unit = runBlocking {

        val request = RouteRequest.newBuilder()
            .setFromNode(RouteNode.newBuilder().setLat(55.1).setLon(37.1).build())
            .setToNode(RouteNode.newBuilder().setLat(55.1).setLon(37.1).build())
            .build()

        val ghResponse = mockk<GHResponse>()
        every { graphHopper.route(any<GHRequest>()) } returns ghResponse
        every { ghResponse.hasErrors() } returns true
        every { ghResponse.errors } returns mutableListOf<Throwable>(RuntimeException("Route not found"))

        assertThatThrownBy { runBlocking { routeGrpcService.getRoute(request) } }
            .isInstanceOf(StatusException::class.java)
            .hasFieldOrPropertyWithValue("status.code", Status.Code.NOT_FOUND)
            .hasMessageContaining("Path does not founded")
    }

    @Test
    fun `getRoute should return valid response on success`() = runBlocking {

        val request = RouteRequest.newBuilder()
            .setFromNode(RouteNode.newBuilder().setLat(55.0).setLon(37.0).build())
            .setToNode(RouteNode.newBuilder().setLat(55.1).setLon(37.1).build())
            .build()

        val ghResponse = mockk<GHResponse>()
        val path = mockk<ResponsePath>()
        val pointList = PointList(2, false).apply {
            add(55.0, 37.0)
            add(55.1, 37.1)
        }

        every { graphHopper.route(any()) } returns ghResponse
        every { ghResponse.hasErrors() } returns false
        every { ghResponse.best } returns path
        every { path.distance } returns 1500.5
        every { path.time } returns 60000L
        every { path.points } returns pointList

        val response = routeGrpcService.getRoute(request)

        assertThat(response.distance).isEqualTo(1500.5)
        assertThat(response.time).isEqualTo(60000L)
        assertThat(response.toNode.lat).isEqualTo(55.1)
        assertThat(response.encodedPath).isNotBlank()

        verify(exactly = 1) { graphHopper.route(any()) }
    }

    @Test
    fun `snapToNode should return node id on success`(): Unit = runBlocking {

        val locationIndex = mockk<LocationIndex>()
        val queryResult = mockk<Snap>()

        every { graphHopper.locationIndex } returns locationIndex
        every { locationIndex.findClosest(55.0, 37.0, EdgeFilter.ALL_EDGES) } returns queryResult
        every { queryResult.isValid } returns true
        every { queryResult.closestNode } returns 42

        val request = SnapRequest.newBuilder().setLat(55.0).setLon(37.0).build()

        val response = routeGrpcService.snapToNode(request)

        assertThat(response.nodeId).isEqualTo(42L)
    }

    @Test
    fun `snapToNode should throw NOT_FOUND when coordinates are invalid`(): Unit = runBlocking {

        val locationIndex = mockk<LocationIndex>()
        val queryResult = mockk<Snap>()

        every { graphHopper.locationIndex } returns locationIndex
        every { locationIndex.findClosest(55.0, 37.0, EdgeFilter.ALL_EDGES) } returns queryResult
        every { queryResult.isValid } returns false

        val request = SnapRequest.newBuilder().setLat(55.0).setLon(37.0).build()

        assertThatThrownBy { runBlocking { routeGrpcService.snapToNode(request) } }
            .isInstanceOf(StatusException::class.java)
            .hasFieldOrPropertyWithValue("status.code", Status.Code.NOT_FOUND)
    }

    @Test
    fun `getBoundaryConnections should return empty default instance if start node is invalid`() = runBlocking {

        val locationIndex = mockk<LocationIndex>()
        val startQr = mockk<Snap>()

        every { graphHopper.locationIndex } returns locationIndex
        every { locationIndex.findClosest(any(), any(), EdgeFilter.ALL_EDGES) } returns startQr
        every { startQr.isValid } returns false

        val request = BoundaryConnectionsRequest.newBuilder()
            .setPoint(RouteNode.newBuilder().setLat(55.0).setLon(37.0).build())
            .build()

        val response = routeGrpcService.getBoundaryConnections(request)

        assertThat(response.connectionsMap).isEmpty()
    }

    @Test
    fun `getBoundaryConnections should traverse graph and return weights`() = runBlocking {

        val locationIndex = mockk<LocationIndex>()
        val startQr = mockk<Snap>()
        val boundaryQr = mockk<Snap>()

        val baseGraph = mockk<BaseGraph>()
        val explorer = mockk<EdgeExplorer>()
        val edgeIterator = mockk<EdgeIterator>()
        val weighting = mockk<Weighting>()
        val profile = mockk<Profile>()

        every { graphHopper.locationIndex } returns locationIndex

        every { locationIndex.findClosest(55.0, 37.0, EdgeFilter.ALL_EDGES) } returns startQr
        every { startQr.isValid } returns true
        every { startQr.closestNode } returns 1

        every { locationIndex.findClosest(55.1, 37.1, EdgeFilter.ALL_EDGES) } returns boundaryQr
        every { boundaryQr.isValid } returns true
        every { boundaryQr.closestNode } returns 2

        every { graphHopper.getProfile("robot_profile") } returns profile
        every { graphHopper.createWeighting(profile, any()) } returns weighting
        every { graphHopper.baseGraph } returns baseGraph
        every { baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES) } returns explorer
        every { baseGraph.nodes } returns 5

        every { explorer.setBaseNode(1) } returns edgeIterator
        every { edgeIterator.next() } returnsMany listOf(true, false)
        every { edgeIterator.adjNode } returns 2
        every { weighting.calcEdgeWeight(edgeIterator, false) } returns 15.5

        every { explorer.setBaseNode(2) } returns edgeIterator

        val boundaryId = 999L
        val request = BoundaryConnectionsRequest.newBuilder()
            .setPoint(RouteNode.newBuilder().setLat(55.0).setLon(37.0).build())
            .setIsStart(true)
            .addBoundaries(
                BoundaryNode.newBuilder()
                    .setId(boundaryId)
                    .setLat(55.1).setLon(37.1)
                    .build()
            )
            .build()

        val response = routeGrpcService.getBoundaryConnections(request)

        assertThat(response.connectionsMap).hasSize(1)
        assertThat(response.connectionsMap[boundaryId]).isEqualTo(15L)

        verify { locationIndex.findClosest(55.0, 37.0, any()) }
        verify { locationIndex.findClosest(55.1, 37.1, any()) }
        verify { baseGraph.createEdgeExplorer(any()) }
    }
}