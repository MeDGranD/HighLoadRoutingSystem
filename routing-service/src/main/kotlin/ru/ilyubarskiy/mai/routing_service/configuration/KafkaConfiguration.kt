package ru.ilyubarskiy.mai.routing_service.configuration

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonSerializer
import ru.ilyubarskiy.mai.routing_service.domen.kafka.RegionWeightsUpdate

@Configuration
class KafkaConfiguration(
    @Value("\${app.kafka.weights-topic}") private val topicName: String,
    @Value("\${spring.kafka.bootstrap-servers:localhost:9092}")
    private val bootstrapServers: String
) {
    @Bean
    fun regionWeightsTopic(): NewTopic {
        return TopicBuilder.name(topicName)
            .partitions(6)
            .replicas(1)
            .config(org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG, org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_COMPACT)
            .build()
    }

    @Bean
    fun producerFactory(): ProducerFactory<String, RegionWeightsUpdate> {
        val configProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
            ProducerConfig.MAX_REQUEST_SIZE_CONFIG to 150_000_000,
            ProducerConfig.BUFFER_MEMORY_CONFIG to 200_000_000,
            ProducerConfig.COMPRESSION_TYPE_CONFIG to "zstd"
        )
        return DefaultKafkaProducerFactory(configProps)
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, RegionWeightsUpdate> {
        return KafkaTemplate(producerFactory())
    }
}