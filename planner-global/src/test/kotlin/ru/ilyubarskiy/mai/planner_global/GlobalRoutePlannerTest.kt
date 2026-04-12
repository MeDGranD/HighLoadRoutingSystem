package ru.ilyubarskiy.mai.planner_global

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.ilyubarskiy.mai.generated.RouteNode
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalEdge
import ru.ilyubarskiy.mai.planner_global.domen.overlay.GlobalNode
import ru.ilyubarskiy.mai.planner_global.service.overlay.OverlayGraphState
import ru.ilyubarskiy.mai.planner_global.service.routing.GlobalRoutePlanner
import java.util.concurrent.ConcurrentHashMap

class GlobalRoutePlannerTest {

    private lateinit var graphState: OverlayGraphState
    private lateinit var planner: GlobalRoutePlanner

    // Константы для тестов
    private val roverSpeed = 5.0 // м/с

    @BeforeEach
    fun setUp() {
        graphState = mockk()
        planner = GlobalRoutePlanner(graphState)
    }

    @Test
    fun `should find straight path when optimal`() {
        // Arrange
        val node1 = GlobalNode(1L, 55.0, 37.0, 1)
        val node2 = GlobalNode(2L, 55.01, 37.01, 1)
        val node3 = GlobalNode(3L, 55.02, 37.02, 1)

        val edge1to2 = GlobalEdge(fromNodeId = 1L, toNodeId = 2L, isTransit = false, regionId = 1, timeMillis = 10000L)
        val edge2to3 = GlobalEdge(fromNodeId = 2L, toNodeId = 3L, isTransit = false, regionId = 1, timeMillis = 10000L)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(1L to node1, 2L to node2, 3L to node3))
        every { graphState.adjacencyList } returns ConcurrentHashMap(mapOf(
            1L to mutableListOf(edge1to2),
            2L to mutableListOf(edge2to3),
            3L to mutableListOf()
        ))

        val startPoint = RouteNode.newBuilder().setLat(54.99).setLon(36.99).build()
        val endPoint = RouteNode.newBuilder().setLat(55.03).setLon(37.03).build()

        val startConnections = mapOf(1L to 5000L) // Старт подключен к узлу 1
        val endConnections = mapOf(3L to 5000L)   // Финиш подключен к узлу 3

        // Act
        val path = planner.findGlobalPathWithVirtualNodes(
            startPoint, startConnections, endPoint, endConnections, roverSpeed
        )

        // Assert
        assertThat(path).hasSize(2)
        assertThat(path[0].toNodeId).isEqualTo(2L)
        assertThat(path[1].toNodeId).isEqualTo(3L)
    }

    @Test
    fun `should bypass short but slow path and choose longer but faster one`() {
        // Arrange
        val node1 = GlobalNode(1L, 55.0, 37.0, 1)
        val node2 = GlobalNode(2L, 55.1, 37.1, 1)
        val node3 = GlobalNode(3L, 55.2, 37.2, 1)
        val node4 = GlobalNode(4L, 55.3, 37.3, 1)

        val edge1to2 = GlobalEdge(fromNodeId = 1L, toNodeId = 2L, isTransit = false, regionId = 1, timeMillis = 50000L) // Пробка!
        val edge2to4 = GlobalEdge(fromNodeId = 2L, toNodeId = 4L, isTransit = false, regionId = 1, timeMillis = 10000L)
        val edge1to3 = GlobalEdge(fromNodeId = 1L, toNodeId = 3L, isTransit = false, regionId = 1, timeMillis = 10000L) // Свободная дорога
        val edge3to4 = GlobalEdge(fromNodeId = 3L, toNodeId = 4L, isTransit = false, regionId = 1, timeMillis = 10000L)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(1L to node1, 2L to node2, 3L to node3, 4L to node4))
        every { graphState.adjacencyList } returns ConcurrentHashMap(mapOf(
            1L to mutableListOf(edge1to2, edge1to3),
            2L to mutableListOf(edge2to4),
            3L to mutableListOf(edge3to4),
            4L to mutableListOf()
        ))

        val startConnections = mapOf(1L to 0L)
        val endConnections = mapOf(4L to 0L)

        val startPoint = RouteNode.newBuilder().setLat(55.0).setLon(37.0).build()
        val endPoint = RouteNode.newBuilder().setLat(55.3).setLon(37.3).build()

        // Act
        val path = planner.findGlobalPathWithVirtualNodes(
            startPoint, startConnections, endPoint, endConnections, roverSpeed
        )

        // Assert
        assertThat(path).hasSize(2)
        assertThat(path[0].toNodeId).isEqualTo(3L)
        assertThat(path[1].toNodeId).isEqualTo(4L)
    }

    @Test
    fun `should throw RuntimeException when path does not exist`() {
        // Arrange
        val node1 = GlobalNode(1L, 55.0, 37.0, 1)
        val node2 = GlobalNode(2L, 55.1, 37.1, 1)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(1L to node1, 2L to node2))
        every { graphState.adjacencyList } returns ConcurrentHashMap(mapOf(
            1L to mutableListOf(), // Тупик
            2L to mutableListOf()
        ))

        val startConnections = mapOf(1L to 0L)
        val endConnections = mapOf(2L to 0L)

        val startPoint = RouteNode.newBuilder().setLat(55.0).setLon(37.0).build()
        val endPoint = RouteNode.newBuilder().setLat(55.1).setLon(37.1).build()

        // Act & Assert
        assertThatThrownBy {
            planner.findGlobalPathWithVirtualNodes(
                startPoint, startConnections, endPoint, endConnections, roverSpeed
            )
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("Global path cannot be founded")
    }

    @Test
    fun `should ignore impassable edges with Long MAX_VALUE`() {
        // Arrange
        val node1 = GlobalNode(1L, 55.0, 37.0, 1)
        val node2 = GlobalNode(2L, 55.1, 37.1, 1)

        // Дорога перекрыта (timeMillis = Long.MAX_VALUE)
        val blockedEdge = GlobalEdge(fromNodeId = 1L, toNodeId = 2L, isTransit = false, regionId = 1, timeMillis = Long.MAX_VALUE)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(1L to node1, 2L to node2))
        every { graphState.adjacencyList } returns ConcurrentHashMap(mapOf(
            1L to mutableListOf(blockedEdge),
            2L to mutableListOf()
        ))

        val startConnections = mapOf(1L to 0L)
        val endConnections = mapOf(2L to 0L)

        val startPoint = RouteNode.newBuilder().setLat(55.0).setLon(37.0).build()
        val endPoint = RouteNode.newBuilder().setLat(55.1).setLon(37.1).build()

        // Act & Assert
        assertThatThrownBy {
            planner.findGlobalPathWithVirtualNodes(
                startPoint, startConnections, endPoint, endConnections, roverSpeed
            )
        }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `should return empty list when start and end connect to the same boundary`() {
        // Arrange
        val node1 = GlobalNode(1L, 55.0, 37.0, 1)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(1L to node1))
        every { graphState.adjacencyList } returns ConcurrentHashMap(mapOf(1L to mutableListOf()))

        val startConnections = mapOf(1L to 5000L)
        val endConnections = mapOf(1L to 3000L) // Тот же самый узел

        val startPoint = RouteNode.newBuilder().setLat(55.0).setLon(37.0).build()
        val endPoint = RouteNode.newBuilder().setLat(55.01).setLon(37.01).build()

        // Act
        val path = planner.findGlobalPathWithVirtualNodes(
            startPoint, startConnections, endPoint, endConnections, roverSpeed
        )

        // Assert
        assertThat(path).isEmpty()
    }

    @Test
    fun `should safely skip missing nodes in graph state`() {
        // Arrange
        val node2 = GlobalNode(2L, 55.1, 37.1, 1)
        val node3 = GlobalNode(3L, 55.2, 37.2, 1)

        val edge2to3 = GlobalEdge(fromNodeId = 2L, toNodeId = 3L, isTransit = false, regionId = 1, timeMillis = 1000L)

        every { graphState.nodes } returns ConcurrentHashMap(mapOf(2L to node2, 3L to node3))
        every { graphState.adjacencyList } returns ConcurrentHashMap(mapOf(
            2L to mutableListOf(edge2to3),
            3L to mutableListOf()
        ))

        // 999L - битая/устаревшая ссылка
        val startConnections = mapOf(999L to 0L, 2L to 0L)
        val endConnections = mapOf(3L to 0L)

        val startPoint = RouteNode.newBuilder().setLat(55.0).setLon(37.0).build()
        val endPoint = RouteNode.newBuilder().setLat(55.2).setLon(37.2).build()

        // Act
        val path = planner.findGlobalPathWithVirtualNodes(
            startPoint, startConnections, endPoint, endConnections, roverSpeed
        )

        // Assert
        assertThat(path).hasSize(1)
        assertThat(path[0].toNodeId).isEqualTo(3L)
    }
}