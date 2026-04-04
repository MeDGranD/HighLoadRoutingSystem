package ru.ilyubarskiy.mai.order_service.service

import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import ru.ilyubarskiy.mai.generated.*
import ru.ilyubarskiy.mai.order_service.repository.OrderRepository
import ru.ilyubarskiy.mai.order_service.repository.dto.MissionPayload
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderCreateRequest
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderCreateResponse
import ru.ilyubarskiy.mai.order_service.repository.dto.Point
import ru.ilyubarskiy.mai.order_service.repository.dto.event.KafkaEvent
import ru.ilyubarskiy.mai.order_service.repository.dto.event.OrderCancelKafkaEvent
import ru.ilyubarskiy.mai.order_service.repository.dto.event.OrderCreatedKafkaEvent
import ru.ilyubarskiy.mai.order_service.repository.dto.event.OrderUpdateEvent
import ru.ilyubarskiy.mai.order_service.repository.dto.regionGraph.GraphFile
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderStatusResponse
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderUpdateRequest
import ru.ilyubarskiy.mai.order_service.repository.entity.OrderEntity
import ru.ilyubarskiy.mai.order_service.repository.entity.OrderStatus
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

private val logger = LoggerFactory.getLogger(OrderService::class.java)

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val kafkaTemplate: KafkaTemplate<String, KafkaEvent>,
    private val regionGraph: GraphFile,
    @Value("\${app.grpc.rover-host}") private val roverHost: String,
    @Value("\${app.grpc.mission-host}") private val missionHost: String,
    private val stringRedisTemplate: StringRedisTemplate
) {

    private val missionChannel = ManagedChannelBuilder.forAddress(missionHost, 9090).usePlaintext().build()
    private val roverChannel = ManagedChannelBuilder.forAddress(roverHost, 9090).usePlaintext().build()

    private val roverService = RoverServiceGrpcKt.RoverServiceCoroutineStub(roverChannel)
    private val missionService = MissionServiceGrpcKt.MissionServiceCoroutineStub(missionChannel)

    fun createOrder(request: OrderCreateRequest): OrderCreateResponse {

        val regionId = getRegionIdByPoint(request.from)
        if(regionId == -1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot create order from this point: ${request.from.lat} ${request.from.lon}")
        }

        val entity = OrderEntity.createNew {
            OrderEntity(
                capacity = request.capacityNeed,
                status = OrderStatus.ROUTING,
                fromLat = request.from.lat,
                fromLon = request.from.lon,
                toLat = request.to.lat,
                toLon = request.to.lon,
                regionId = regionId
            )
        }

        return orderRepository.save(entity).let {

            val event = (OrderCreatedKafkaEvent(
                orderId = it.id,
                from = request.from,
                to = request.to,
                capacity = request.capacityNeed,
            ) as KafkaEvent)

            kafkaTemplate.send("order-planner-event", entity.id.toString(), event).whenComplete { result, ex ->
                if (ex == null) {
                    logger.info("Order ${entity.id} successfully sent to partition ${result.recordMetadata.partition()}")
                } else {
                    logger.error("Sending error for order ${entity.id} in Kafka", ex)
                }
            }
            OrderCreateResponse(it.id)
        }

    }

    fun getOrder(orderId: UUID): OrderStatusResponse {

        val orderEntity = orderRepository.findById(orderId).orElseThrow {
            RuntimeException("Cannot find order with id $orderId")
        }

        val errors = stringRedisTemplate.opsForList().size("errors:${orderId}").let { size ->
            buildList {
                for(i in 1..size)
                    add(stringRedisTemplate.opsForList().leftPop("errors:${orderId}"))
            }
        }

        if(errors.isNotEmpty()){
            orderRepository.updateOrderStatus(orderId, OrderStatus.FAILED.value)
        }

        var mission: GetByOrderIdResponse?
        var rover: RoverStatusResponse?
        runBlocking {
            try {
                mission = missionService.getByOrderId(GetByOrderIdRequest.newBuilder().setOrderId(orderId.toString()).build())
                rover = roverService.getRoverStatus(RoverStatusRequest.newBuilder().setRoverId(mission!!.roverId).build())
            } catch (_ : Exception){
                logger.warn("Cannot find mission or rover for the order: $orderId")
                mission = null
                rover = null
            }

        }

        val details = if(mission != null && rover != null) {
            val payload = jacksonObjectMapper().readValue<MissionPayload>(mission!!.payloadJson)
            val time = payload.orders.find { it.order_id == orderId.toString() }!!.estimated_time_sec
            val distance = payload.orders.find { it.order_id == orderId.toString() }!!.distance_m
            OrderStatusResponse.Details(
                robotLat = rover!!.lat,
                robotLon = rover!!.lon,
                time = time.toLong(),
                distance = distance
            )
        } else null

        return OrderStatusResponse(
            orderId = orderId,
            status = orderEntity.status.value,
            details = details,
            errors = errors
        )
    }

    fun updateOrder(orderId: UUID, request: OrderUpdateRequest) {

        val entity = orderRepository.findById(orderId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find order with id: $orderId")
        }
        if (entity.status != OrderStatus.IN_PROGRESS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot update order not in IN_PROGRESS status")
        }

        val event = when(request.cancel){
            true -> createCancelOrderEvent(entity)
            false -> createUpdateOrderEvent(entity, request)
        }

        kafkaTemplate.send("order-planner-event", entity.id.toString(), event).whenComplete { result, ex ->
            if (ex == null) {
                logger.info("Order ${entity.id} successfully sent to partition ${result.recordMetadata.partition()}")
            } else {
                logger.error("Sending error for order ${entity.id} in Kafka", ex)
            }
        }

        if(request.cancel) {
            entity.copy(
                status = OrderStatus.CANCELED
            ).let { orderRepository.save(it) }
        }
    }

    private fun createUpdateOrderEvent(entity: OrderEntity, request: OrderUpdateRequest): KafkaEvent =
        OrderUpdateEvent(
            orderId = entity.id,
            to = request.to,
            from = request.from
        )

    private fun createCancelOrderEvent(entity: OrderEntity): KafkaEvent =
        OrderCancelKafkaEvent(
            orderId = entity.id,
        )

    private fun getRegionIdByPoint(point: Point): Int =
        regionGraph.regions.values.find { it.bbox.minLat <= point.lat && it.bbox.maxLat >= point.lat && it.bbox.minLon <= point.lon && it.bbox.maxLon >= point.lon }?.regionId ?: -1

    @PreDestroy
    fun shutdown() {
        missionChannel.shutdown()
        roverChannel.shutdown()
    }

}