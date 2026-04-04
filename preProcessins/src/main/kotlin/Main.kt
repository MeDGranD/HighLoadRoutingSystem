package ru.ilyubarskiy.mai

import ru.ilyubarskiy.mai.domen.BoundingBox
import ru.ilyubarskiy.mai.region.DynamicGridGenerator
import ru.ilyubarskiy.mai.s3.S3Uploader
import ru.ilyubarskiy.mai.splitter.SystemOrchestrator
import java.io.File

fun main() {
    val inputPbf = File("moscow.osm.pbf")
    val outputDir = File("output_regions")

    val gridGenerator = DynamicGridGenerator()
    val globalBbox = BoundingBox(55.56, 55.91, 37.36, 37.85)
    println("INFO: Глобальные границы: $globalBbox")

    val regions = gridGenerator.generateGrid(globalBbox, stepDegreesLat = 0.09, stepDegreesLon = 0.164)

    val orchestrator = SystemOrchestrator()

    orchestrator.extractAndSaveGraph(inputPbf, outputDir, regions)

    orchestrator.splitWithOsmium(inputPbf, outputDir, regions)
    val minioUploader = S3Uploader(
        endpoint = "http://localhost:9000",
        accessKey = "minioadmin",
        secretKey = "minioadmin",
        bucketName = "graph"
    )
    minioUploader.uploadAndVersion(outputDir)
}