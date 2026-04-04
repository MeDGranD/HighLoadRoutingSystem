package ru.ilyubarskiy.mai.order_service.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import ru.ilyubarskiy.mai.order_service.repository.entity.OrderEntity
import java.util.UUID

@Repository
interface OrderRepository: CrudRepository<OrderEntity, UUID> {

    @Query("""
        UPDATE orders
        SET status = :status
        WHERE id = :orderId
    """)
    @Modifying
    fun updateOrderStatus(orderId: UUID, status: String)

}