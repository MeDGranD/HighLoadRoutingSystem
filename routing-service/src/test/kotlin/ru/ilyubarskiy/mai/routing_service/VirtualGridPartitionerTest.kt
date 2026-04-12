package ru.ilyubarskiy.mai.routing_service

import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.NodeAccess
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.EdgeExplorer
import com.graphhopper.util.EdgeIterator
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import ru.ilyubarskiy.mai.routing_service.service.GraphCacheLoader
import ru.ilyubarskiy.mai.routing_service.service.VirtualGridPartitioner

@ExtendWith(MockitoExtension::class)
class VirtualGridPartitionerTest {

    private lateinit var graphhopper: GraphHopper
    private lateinit var graphCacheLoader: GraphCacheLoader
    private lateinit var partitioner: VirtualGridPartitioner

    private lateinit var baseGraph: BaseGraph
    private lateinit var nodeAccess: NodeAccess
    private lateinit var explorer: EdgeExplorer
    private lateinit var locationIndex: LocationIndex

    @BeforeEach
    fun setUp() {
        graphhopper = mockk()
        graphCacheLoader = mockk()
        baseGraph = mockk()
        nodeAccess = mockk()
        explorer = mockk()
        locationIndex = mockk()

        every { graphhopper.baseGraph } returns baseGraph
        every { graphhopper.locationIndex } returns locationIndex
        every { baseGraph.nodeAccess } returns nodeAccess
        every { baseGraph.createEdgeExplorer() } returns explorer

        every { baseGraph.nodes } returns 0
        every { graphCacheLoader.localBoundaryNodes } returns emptyList()

        partitioner = VirtualGridPartitioner(graphhopper, graphCacheLoader)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `partitionGraph should correctly map valid external boundary nodes`() {
        val boundaryNode = GraphCacheLoader.BoundaryNode(id = 100L, lat = 55.0, lon = 37.0)
        every { graphCacheLoader.localBoundaryNodes } returns listOf(boundaryNode)

        val snap = mockk<Snap>()
        every { locationIndex.findClosest(55.0, 37.0, EdgeFilter.ALL_EDGES) } returns snap
        every { snap.isValid } returns true
        every { snap.closestNode } returns 42

        partitioner.partitionGraph()

        assertThat(partitioner.externalBoundaries).containsExactly(42)
        assertThat(partitioner.internalToOsmBoundary).containsEntry(42, boundaryNode)
    }

    @Test
    fun `partitionGraph should ignore invalid external boundary nodes`() {
        val boundaryNode = GraphCacheLoader.BoundaryNode(id = 101L, lat = 55.0, lon = 37.0)
        every { graphCacheLoader.localBoundaryNodes } returns listOf(boundaryNode)

        val snap = mockk<Snap>()
        every { locationIndex.findClosest(55.0, 37.0, EdgeFilter.ALL_EDGES) } returns snap
        every { snap.isValid } returns false

        partitioner.partitionGraph()

        assertThat(partitioner.externalBoundaries).isEmpty()
        assertThat(partitioner.internalToOsmBoundary).isEmpty()
    }

    @Test
    fun `partitionGraph should assign nodes to correct cells based on coordinates`() {
        every { baseGraph.nodes } returns 2

        every { nodeAccess.getLat(0) } returns 0.01
        every { nodeAccess.getLon(0) } returns 0.01

        every { nodeAccess.getLat(1) } returns 0.03
        every { nodeAccess.getLon(1) } returns 0.03

        val emptyIterator = mockk<EdgeIterator>()
        every { explorer.setBaseNode(any()) } returns emptyIterator
        every { emptyIterator.next() } returns false

        partitioner.partitionGraph()

        assertThat(partitioner.nodeToCell[0]).isEqualTo(0)
        assertThat(partitioner.nodeToCell[1]).isEqualTo(32)

        assertThat(partitioner.cells[0]).containsExactly(0)
        assertThat(partitioner.cells[32]).containsExactly(1)
    }

    @Test
    fun `partitionGraph should identify internal boundaries when edge crosses cells`() {
        every { baseGraph.nodes } returns 2

        every { nodeAccess.getLat(0) } returns 0.01
        every { nodeAccess.getLon(0) } returns 0.01

        every { nodeAccess.getLat(1) } returns 0.03
        every { nodeAccess.getLon(1) } returns 0.03

        val iter0 = mockk<EdgeIterator>()
        every { explorer.setBaseNode(0) } returns iter0
        every { iter0.next() } returnsMany listOf(true, false)
        every { iter0.adjNode } returns 1

        val iter1 = mockk<EdgeIterator>()
        every { explorer.setBaseNode(1) } returns iter1
        every { iter1.next() } returnsMany listOf(true, false)
        every { iter1.adjNode } returns 0

        partitioner.partitionGraph()

        assertThat(partitioner.internalBoundaries).containsExactlyInAnyOrder(0, 1)
    }

    @Test
    fun `partitionGraph should NOT identify internal boundaries if edge is within same cell`() {
        every { baseGraph.nodes } returns 2

        every { nodeAccess.getLat(0) } returns 0.01
        every { nodeAccess.getLon(0) } returns 0.01

        every { nodeAccess.getLat(1) } returns 0.015
        every { nodeAccess.getLon(1) } returns 0.015

        val iter0 = mockk<EdgeIterator>()
        every { explorer.setBaseNode(0) } returns iter0
        every { iter0.next() } returnsMany listOf(true, false)
        every { iter0.adjNode } returns 1

        val emptyIterator = mockk<EdgeIterator>()
        every { explorer.setBaseNode(1) } returns emptyIterator
        every { emptyIterator.next() } returns false

        partitioner.partitionGraph()

        assertThat(partitioner.internalBoundaries).isEmpty()
    }
}