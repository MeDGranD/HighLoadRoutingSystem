package ru.ilyubarskiy.mai.routing_service.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.EdgeExplorer
import com.graphhopper.util.EdgeIterator
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
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
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import ru.ilyubarskiy.mai.routing_service.configuration.wieghting.RobotWeighting
import ru.ilyubarskiy.mai.routing_service.service.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "graph.region=7",
        "app.kafka.weights-topic=region-weights-topic",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=test-matrix-group",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer"
    ]
)
@Testcontainers
class RegionMatrixUpdaterIntegrationTest {

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

    @MockkBean(relaxed = true)
    lateinit var graphCacheLoader: GraphCacheLoader
    @MockkBean
    lateinit var graphhopper: GraphHopper
    @MockkBean lateinit var partitioner: VirtualGridPartitioner
    @MockkBean lateinit var trafficProvider: TrafficProvider
    @MockkBean lateinit var priorityProvider: PriorityProvider

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate
    @Autowired lateinit var updater: RegionMatrixUpdater
    @Autowired lateinit var testKafkaConsumer: KafkaTestListener

    @BeforeEach
    fun setUp() {
        val baseGraph = mockk<BaseGraph>()
        val explorer = mockk<EdgeExplorer>()

        every { graphhopper.baseGraph } returns baseGraph
        every { graphhopper.encodingManager } returns mockk(relaxed = true)
        every { baseGraph.nodes } returns 2
        every { baseGraph.createEdgeExplorer(EdgeFilter.ALL_EDGES) } returns explorer

        val osmBoundary1 = GraphCacheLoader.BoundaryNode(100L, 55.0, 37.0)
        val osmBoundary2 = GraphCacheLoader.BoundaryNode(200L, 55.1, 37.1)

        every { partitioner.cells } returns ConcurrentHashMap(mapOf(1 to mutableListOf(0, 1)))
        every { partitioner.internalBoundaries } returns mutableSetOf()
        every { partitioner.externalBoundaries } returns mutableSetOf(0, 1)
        every { partitioner.nodeToCell } returns intArrayOf(1, 1)
        every { partitioner.internalToOsmBoundary } returns mutableMapOf(0 to osmBoundary1, 1 to osmBoundary2)

        mockkConstructor(RobotWeighting::class)
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

        testKafkaConsumer.messages.clear()
        redisTemplate.delete("region_weights:7")
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `should process graph and save valid json to real redis and kafka`(): Unit = runBlocking {

        Thread.sleep(3000)
        updater.updateRegionMatrices()

        val cachedJson = redisTemplate.opsForValue().get("region_weights:7")
        assertThat(cachedJson).isNotNull()
        assertThat(cachedJson).contains("\"regionId\":7")
        assertThat(cachedJson).contains("\"fromNodeId\":100")
        assertThat(cachedJson).contains("\"toNodeId\":200")

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assertThat(testKafkaConsumer.messages).hasSize(1)

            val kafkaMessage = testKafkaConsumer.messages.poll()
            assertThat(kafkaMessage).isNotNull()

            val payload = jacksonObjectMapper().readTree(kafkaMessage)
            assertThat(payload.get("regionId").asInt()).isEqualTo(7)
            assertThat(payload.get("edges").isArray).isTrue()
            assertThat(payload.get("edges").get(0).get("timeMillis").asLong()).isEqualTo(1500L)
        }
    }

    @TestConfiguration
    @EnableKafka
    class TestConfig {

        @Bean
        fun weightsTopic(): org.apache.kafka.clients.admin.NewTopic {
            return org.apache.kafka.clients.admin.NewTopic("region-weights-topic", 1, 1.toShort())
        }

        @Bean
        fun consumerFactory(): ConsumerFactory<String, String> {
            val props = mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
            )
            return DefaultKafkaConsumerFactory(props)
        }

        @Bean
        fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
            val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
            factory.setConsumerFactory(consumerFactory())
            return factory
        }

        @Bean
        fun testKafkaConsumer(): KafkaTestListener {
            return KafkaTestListener()
        }
    }

    class KafkaTestListener {
        val messages = LinkedBlockingQueue<String>()

        @KafkaListener(topics = ["\${app.kafka.weights-topic}"], groupId = "test-group")
        fun listen(payload: String) {
            println("=== MESSAGE RECEIVED IN TEST LISTENER ===")
            println(payload)
            messages.add(payload)
        }
    }
}