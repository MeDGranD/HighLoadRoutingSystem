package ru.ilyubarskiy.mai.mission_service.repository.entities

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import ru.ilyubarskiy.mai.mission_service.repository.dto.MissionPayload
import java.util.UUID

@Table("missions")
class MissionEntity(
    @Id
    private val id: UUID = UUID.randomUUID(),
    val roverId: UUID,
    var status: String,
    var payload: MissionPayload,
): Persistable<UUID> {

    @Transient
    private var isNew = false

    override fun getId(): UUID = id
    override fun isNew(): Boolean = isNew

    companion object {
        fun createNew(factory: () -> MissionEntity): MissionEntity {
            return factory().apply {
                isNew = true
            }
        }
    }

}