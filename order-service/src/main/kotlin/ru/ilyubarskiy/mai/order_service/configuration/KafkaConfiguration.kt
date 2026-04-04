package ru.ilyubarskiy.mai.order_service.configuration

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import java.util.*

@Configuration
@EnableKafka
class KafkaConfiguration {

    private val DEFAULT_PARTITIONS = 3
    private val DEFAULT_REPLICAS = 1

    @Bean
    fun orderPlannerEventTopic(): NewTopic {
        return TopicBuilder.name("order-planner-event")
            .partitions(DEFAULT_PARTITIONS)
            .replicas(DEFAULT_REPLICAS)
            .build()
    }

    @Bean
    fun orderRoverEventTopic(): NewTopic {
        return TopicBuilder.name("order-rover-event")
            .partitions(DEFAULT_PARTITIONS)
            .replicas(DEFAULT_REPLICAS)
            .build()
    }

}