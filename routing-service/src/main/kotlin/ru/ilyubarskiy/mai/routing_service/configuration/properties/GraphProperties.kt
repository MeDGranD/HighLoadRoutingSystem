package ru.ilyubarskiy.mai.routing_service.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graph")
data class GraphProperties(
    val bucket: String,
    val region: String
)