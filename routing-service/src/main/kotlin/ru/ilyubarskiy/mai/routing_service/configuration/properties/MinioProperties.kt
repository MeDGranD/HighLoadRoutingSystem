package ru.ilyubarskiy.mai.routing_service.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "minio")
data class MinioProperties (
    val endpoint: String,
    val accessKey: String,
    val secretKey: String
)
