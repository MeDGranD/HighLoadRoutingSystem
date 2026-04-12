package ru.ilyubarskiy.mai.routing_service

import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.EdgeExplorer
import com.graphhopper.util.EdgeIterator
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import ru.ilyubarskiy.mai.routing_service.configuration.wieghting.RobotWeighting
import ru.ilyubarskiy.mai.routing_service.domen.kafka.RegionWeightsUpdate
import ru.ilyubarskiy.mai.routing_service.service.*
import java.util.concurrent.ConcurrentHashMap

class RegionMatrixUpdaterTest {

    private lateinit var graphhopper: GraphHopper
    private lateinit var partitioner: VirtualGridPartitioner
    private lateinit var kafkaTemplate: KafkaTemplate<String, RegionWeightsUpdate>
    private lateinit var trafficProvider: TrafficProvider
    private lateinit var priorityProvider: PriorityProvider
    private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var updater: RegionMatrixUpdater
    private lateinit var valueOps: ValueOperations<String, String>

    private val regionId = 7
    private val topicName = "region-weights-topic"

    @BeforeEach
    fun setUp() {
        graphhopper = mockk()
        partitioner = mockk()
        kafkaTemplate = mockk(relaxed = true)
        trafficProvider = mockk()
        priorityProvider = mockk()
        redisTemplate = mockk()

        valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps

        updater = RegionMatrixUpdater(
            graphhopper, partitioner, kafkaTemplate,
            trafficProvider, priorityProvider, redisTemplate,
            regionId, topicName
        )

        mockkConstructor(RobotWeighting::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `updateRegionMatrices should successfully calculate paths and publish payload`(): Unit = runBlocking {
        val baseGraph = mockk<BaseGraph>()
        val encodingManager = mockk<EncodingManager>(relaxed = true)
        val explorer = mockk<EdgeExplorer>()

        every { graphhopper.baseGraph } returns baseGraph
        every { graphhopper.encodingManager } returns encodingManager
        every { baseGraph.nodes } returns 2
        every { baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES) } returns explorer

        val cellId = 1
        val osmBoundary1 = GraphCacheLoader.BoundaryNode(100L, 55.0, 37.0)
        val osmBoundary2 = GraphCacheLoader.BoundaryNode(200L, 55.1, 37.1)

        every { partitioner.cells } returns ConcurrentHashMap(mapOf(cellId to mutableListOf(0, 1)))
        every { partitioner.internalBoundaries } returns mutableSetOf()
        every { partitioner.externalBoundaries } returns mutableSetOf(0, 1)
        every { partitioner.nodeToCell } returns intArrayOf(cellId, cellId)
        every { partitioner.internalToOsmBoundary } returns mutableMapOf(
            0 to osmBoundary1,
            1 to osmBoundary2
        )

        every { anyConstructed<RobotWeighting>().calcEdgeWeight(any(), any()) } returns 15.0
        every { anyConstructed<RobotWeighting>().calcEdgeMillis(any(), any()) } returns 1500L

        val iter0 = mockk<EdgeIterator>()
        every { explorer.setBaseNode(0) } returns iter0
        every { iter0.next() } returnsMany listOf(true, false)
        every { iter0.adjNode } returns 1
        every { iter0.distance } returns 150.0

        val iter1 = mockk<EdgeIterator>()
        every { explorer.setBaseNode(1) } returns iter1
        every { iter1.next() } returns false

        updater.updateRegionMatrices()

        val payloadSlot = slot<String>()

        verify(exactly = 1) { valueOps.set("region_weights:7", capture(payloadSlot)) }

        val kafkaPayloadSlot = slot<RegionWeightsUpdate>()
        verify(exactly = 1) { kafkaTemplate.send("region-weights-topic", "7", capture(kafkaPayloadSlot)) }

        val payload = kafkaPayloadSlot.captured
        assertThat(payload.regionId).isEqualTo(7)
        assertThat(payload.edges).hasSize(1)

        val edge = payload.edges.first()
        assertThat(edge.fromNodeId).isEqualTo(100L)
        assertThat(edge.toNodeId).isEqualTo(200L)
        assertThat(edge.distanceMeters).isEqualTo(150.0)
        assertThat(edge.timeMillis).isEqualTo(1500L)
    }

    @Test
    fun `updateRegionMatrices should handle empty grid gracefully`() = runBlocking {
        val baseGraph = mockk<BaseGraph>()
        every { graphhopper.baseGraph } returns baseGraph
        every { graphhopper.encodingManager } returns mockk(relaxed = true)
        every { baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES) } returns mockk()
        every { baseGraph.nodes } returns 0

        every { partitioner.cells } returns ConcurrentHashMap()
        every { partitioner.internalBoundaries } returns mutableSetOf()
        every { partitioner.externalBoundaries } returns mutableSetOf()

        updater.updateRegionMatrices()

        val payloadSlot = slot<RegionWeightsUpdate>()
        verify(exactly = 1) { kafkaTemplate.send("region-weights-topic", "7", capture(payloadSlot)) }

        assertThat(payloadSlot.captured.edges).isEmpty()
    }
}