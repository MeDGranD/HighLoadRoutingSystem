package ru.ilyubarskiy.mai.splitter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import crosby.binary.osmosis.OsmosisReader
import ru.ilyubarskiy.mai.domen.BoundingBox
import java.io.File
import java.io.FileInputStream

class SystemOrchestrator {

    fun extractAndSaveGraph(inputPbf: File, outputDir: File, regions: Map<Int, BoundingBox>) {
        if (!outputDir.exists()) outputDir.mkdirs()

        println("Step 1: Поиск граничных узлов и сборка region_graph")
        val topologyExtractor = TopologyExtractor(regions)
        val reader = OsmosisReader(FileInputStream(inputPbf))

        reader.setSink(topologyExtractor)
        reader.run()

        val mapper = jacksonObjectMapper()
        val graphFile = File(outputDir, "region_graph.json")
        mapper.writerWithDefaultPrettyPrinter().writeValue(graphFile, topologyExtractor.regionGraph)

        println("INFO: Граф связности (${topologyExtractor.countTotalLinks()} переходов) успешно сохранен в: ${graphFile.absolutePath}")
    }

    fun splitWithOsmium(inputPbf: File, outputDir: File, regions: Map<Int, BoundingBox>) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val batchSize = 25
        val regionEntries = regions.entries.toList()
        val totalBatches = (regionEntries.size + batchSize - 1) / batchSize
        val mapper = jacksonObjectMapper()

        println("Step 2: Физическая нарезка PBF через Osmium")

        for ((batchIndex, batch) in regionEntries.chunked(batchSize).withIndex()) {
            println("INFO: -> Запуск Osmium: батч ${batchIndex + 1}/$totalBatches (Регионы: ${batch.first().key} - ${batch.last().key})...")

            val bufferLat = 0.005 // ~550 метров
            val bufferLon = 0.008 // ~500 метров
            val extracts = batch.map { (regionId, bbox) ->
                mapOf(
                    "output" to "region_$regionId.osm.pbf",
                    "bbox" to listOf(
                        bbox.minLon - bufferLon,
                        bbox.minLat - bufferLat,
                        bbox.maxLon + bufferLon,
                        bbox.maxLat + bufferLat
                    ),
                    "output_format" to "pbf"
                )
            }

            val configMap = mapOf(
                "directory" to outputDir.absolutePath,
                "extracts" to extracts
            )

            val configFile = File("osmium_batch.json")
            mapper.writeValue(configFile, configMap)

            val process = ProcessBuilder(
                "osmium", "extract",
                "-c", configFile.absolutePath,
                inputPbf.absolutePath,
                "--overwrite",
                "-s", "complete_ways"
            ).inheritIO().start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                println("ERROR: Ошибка при выполнении Osmium! Код: $exitCode")
                return
            }

            configFile.delete()
        }

        println("INFO: Вся физическая нарезка через Osmium успешно завершена!")
    }
}