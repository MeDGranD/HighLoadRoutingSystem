package ru.ilyubarskiy.mai.mission_service.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import ru.ilyubarskiy.mai.mission_service.repository.entities.MissionEntity
import java.util.UUID

interface MissionRepository : CrudRepository<MissionEntity, UUID> {

    @Query("""
            SELECT * FROM missions 
            WHERE payload->'orders' @> cast(CONCAT('[{"order_id": "', :orderId, '"}]') as jsonb)   
    """)
    fun findByOrderId(orderId: String): MissionEntity?

    @Query("""
        SELECT * FROM missions m
        WHERE rover_id = :roverId AND status = 'IN_PROGRESS'
    """)
    fun getActiveByRoverId(roverId: UUID): List<MissionEntity>

    @Query("""
        UPDATE missions
        SET status = :status
        WHERE id = :missionId
    """)
    @Modifying
    fun updateStatus(missionId: UUID, status: String)

}