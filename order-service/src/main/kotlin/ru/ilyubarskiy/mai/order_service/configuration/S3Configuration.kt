package ru.ilyubarskiy.mai.order_service.configuration

import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.ilyubarskiy.mai.order_service.configuration.properties.S3Properties
import ru.ilyubarskiy.mai.order_service.repository.dto.regionGraph.GraphFile
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

@Configuration
@EnableConfigurationProperties(S3Properties::class)
class S3Configuration(
    private val properties: S3Properties,
) {

    @Bean
    fun minio(): MinioClient =
        MinioClient.builder()
            .endpoint(properties.endpoint)
            .credentials(properties.accessKey, properties.secretKey)
            .build()

    @Bean
    fun regionGraph(minioClient: MinioClient): GraphFile {

        val version = minioClient.getObject(GetObjectArgs.builder().bucket(properties.bucket).`object`("version.txt").build())
            .use { it.bufferedReader().readText().trim() }

        val graphStream = minioClient.getObject(GetObjectArgs.builder().bucket(properties.bucket).`object`("v$version/region_graph.json").build())
        return graphStream.use{ jacksonObjectMapper().readValue(it) }
    }

}