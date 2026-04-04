package ru.ilyubarskiy.mai.rover_facade.domen

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import ru.ilyubarskiy.mai.rover_facade.domen.dto.RoverTelemetryPayload
import java.util.concurrent.ConcurrentLinkedQueue

@Repository
class ClickHouseTelemetryWriter(
    private val clickHouseJdbcTemplate: JdbcTemplate
) {
    private val batchQueue = ConcurrentLinkedQueue<RoverTelemetryPayload>()

    fun pushToQueue(telemetry: RoverTelemetryPayload) {
        batchQueue.add(telemetry)
    }

    @Scheduled(fixedDelay = 5000)
    fun flushBatch() {
        if (batchQueue.isEmpty()) return

        val batch = mutableListOf<Array<Any>>()
        while (batchQueue.isNotEmpty() && batch.size < 10000) {
            batchQueue.poll()?.let {
                batch.add(arrayOf(it.roverId, it.lat, it.lon, it.battery, it.timestamp))
            }
        }

        val sql = "INSERT INTO rover_telemetry (rover_id, lat, lon, battery, ts) VALUES (?, ?, ?, ?, ?)"

        clickHouseJdbcTemplate.batchUpdate(sql, batch)
    }
}