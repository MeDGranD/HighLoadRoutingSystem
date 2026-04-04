package ru.ilyubarskiy.mai.rover_service.configuration

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.TopicBuilder

@Configuration
@EnableKafka
class KafkaConfiguration {

    private val DEFAULT_PARTITIONS = 3
    private val DEFAULT_REPLICAS = 1

    @Bean
    fun orderPlannerEventTopic(): NewTopic {
        return TopicBuilder.name("rover-mission-event")
            .partitions(DEFAULT_PARTITIONS)
            .replicas(DEFAULT_REPLICAS)
            .build()
    }

}