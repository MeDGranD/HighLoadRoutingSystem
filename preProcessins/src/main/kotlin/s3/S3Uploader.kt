package ru.ilyubarskiy.mai.s3

import io.minio.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class S3Uploader(
    endpoint: String = "http://localhost:9000",
    accessKey: String = "minioadmin",
    secretKey: String = "minioadmin",
    private val bucketName: String = "graph"
) {
    private val minioClient = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build()

    fun uploadAndVersion(outputDir: File) {
        if (!outputDir.exists() || outputDir.listFiles()?.isEmpty() == true) {
            println("ERROR: Директория $outputDir пуста или не существует.")
            return
        }

        println("Step 3: Загрузка в MinIO")

        val bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())
        if (!bucketExists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build())
            println("INFO: Бакет '$bucketName' успешно создан.")
        }

        var version = 1
        try {
            val response = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`("version.txt")
                    .build()
            )
            val currentVersionStr = response.readBytes().toString(StandardCharsets.UTF_8).trim()
            version = currentVersionStr.toInt() + 1
            response.close()
        } catch (e: Exception) {
            println(" INFO:Файл version.txt не найден в бакете. Начинаем с версии 1.")
        }

        val versionBytes = version.toString().toByteArray(StandardCharsets.UTF_8)
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`("version.txt")
                .stream(ByteArrayInputStream(versionBytes), versionBytes.size.toLong(), -1)
                .contentType("text/plain")
                .build()
        )
        println("INFO: Текущая версия графа установлена на: v$version")

        val filesToUpload = outputDir.listFiles() ?: return

        for (file in filesToUpload) {
            if (file.isFile) {
                val objectName = if (file.extension == "pbf" || file.name == "region_graph.json") {
                    "v${version}/${file.name}"
                } else {
                    file.name
                }

                minioClient.uploadObject(
                    UploadObjectArgs.builder()
                        .bucket(bucketName)
                        .`object`(objectName)
                        .filename(file.absolutePath)
                        .build()
                )
                println("INFO: -> Загружен: $objectName")
            }
        }

        println("INFO: Все файлы успешно загружены в MinIO (бакет: $bucketName)!")
    }
}