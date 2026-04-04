package ru.ilyubarskiy.mai.rover_service.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import ru.ilyubarskiy.mai.rover_service.repository.entity.RoverEntity
import java.time.Instant
import java.util.UUID

@Repository
interface RoverRepository: CrudRepository<RoverEntity, UUID> {

    @Query("""
        SELECT *
        FROM rovers
        WHERE status in (:status)
    """)
    fun findAllInStatus(status: Iterable<String>): List<RoverEntity>

    @Query("""
        UPDATE rovers
        SET last_seen_lat = :lat,
            last_seen_lon = :lon,
            last_seen_battery = :battery,
            last_seen = :ts
        WHERE id = :roverId
    """)
    @Modifying
    fun updateLastLocation(
        roverId: UUID,
        lat: Double,
        lon: Double,
        battery: Double,
        ts: Long
    )

    @Query("""
        UPDATE rovers
        SET status = :status
        WHERE id = :roverId
    """)
    @Modifying
    fun updateStatus(roverId: UUID, status: String)

}