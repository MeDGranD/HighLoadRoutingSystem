package ru.ilyubarskiy.mai.order_service.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "minio")
data class S3Properties(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String
)