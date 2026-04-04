package ru.ilyubarskiy.mai.order_service.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("orders")
data class OrderEntity(
    @Id
    private val id: UUID = UUID.randomUUID(),
    val capacity: Double,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val status: OrderStatus,
    val regionId: Int
): Persistable<UUID> {

    @Transient
    private var isNew = false

    override fun getId(): UUID = id
    override fun isNew(): Boolean = isNew

    companion object {
        fun createNew(factory: () -> OrderEntity): OrderEntity {
            return factory().apply {
                isNew = true
            }
        }
    }

}