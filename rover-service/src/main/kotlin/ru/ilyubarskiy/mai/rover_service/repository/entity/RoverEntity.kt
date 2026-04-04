package ru.ilyubarskiy.mai.rover_service.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("rovers")
data class RoverEntity(
    @Id
    private val id: UUID,
    val modelId: UUID,
    val lastSeenLat: Double,
    val lastSeenLon: Double,
    val lastSeenBattery: Double,
    val status: RoverStatus,
    val lastSeen: Instant
): Persistable<UUID> {

    @Transient
    private var isNew = false

    override fun getId(): UUID = id
    override fun isNew(): Boolean = isNew

    companion object {
        fun createNew(factory: () -> RoverEntity): RoverEntity {
            return factory().apply {
                isNew = true
            }
        }
    }

}
