package ru.ilyubarskiy.mai.rover_service.repository

import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import ru.ilyubarskiy.mai.rover_service.repository.entity.RoverModelEntity
import java.util.*

@Repository
interface RoverModelRepository: CrudRepository<RoverModelEntity, UUID>