package ru.ilyubarskiy.mai.planner_global.configuration.s3

import io.minio.MinioClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class MinioConfiguration(private val properties: MinioProperties) {

    @Bean
    fun minioClient(): MinioClient {
        return MinioClient.builder()
            .endpoint(properties.endpoint)
            .credentials(properties.accessKey, properties.secretKey)
            .build()
    }
}