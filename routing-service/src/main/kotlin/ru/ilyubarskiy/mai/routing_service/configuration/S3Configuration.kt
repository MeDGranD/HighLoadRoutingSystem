package ru.ilyubarskiy.mai.routing_service.configuration

import io.minio.MinioClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.ilyubarskiy.mai.routing_service.configuration.properties.MinioProperties

@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class S3Configuration(
    private val minioProperties: MinioProperties
) {

    @Bean
    fun minioClient(): MinioClient =
        MinioClient.builder()
            .endpoint(minioProperties.endpoint)
            .credentials(minioProperties.accessKey, minioProperties.secretKey)
            .build()

}