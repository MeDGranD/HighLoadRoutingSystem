package ru.ilyubarskiy.mai.rover_service.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.*

@Table("models")
data class RoverModelEntity(
    @Id
    private val id: UUID,
    val power: Double,
    val maxCharge: Double,
    val capacity: Double,
    val maxSpeed: Double,
    val avgSpeed: Double
): Persistable<UUID> {

    @Transient
    private var isNew = false

    override fun getId(): UUID = id
    override fun isNew(): Boolean = isNew

    companion object {
        fun createNew(factory: () -> RoverModelEntity): RoverModelEntity {
            return factory().apply {
                isNew = true
            }
        }
    }

}