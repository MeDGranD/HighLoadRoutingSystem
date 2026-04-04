package ru.ilyubarskiy.mai.order_service.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderCreateRequest
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderCreateResponse
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderStatusResponse
import ru.ilyubarskiy.mai.order_service.repository.dto.rest.OrderUpdateRequest
import ru.ilyubarskiy.mai.order_service.service.OrderService
import java.util.UUID

@RestController
class OrderController(
    private val orderService: OrderService
) {

    @PostMapping("/api/v1/order")
    fun createOrder(@RequestBody request: OrderCreateRequest): ResponseEntity<OrderCreateResponse> {
        val response = orderService.createOrder(request)
        return ResponseEntity(response, HttpStatus.CREATED)
    }

    @GetMapping("/api/v1/order/{orderId}")
    fun getOrderStatus(@PathVariable("orderId") orderId: UUID): ResponseEntity<OrderStatusResponse> {
        val response = orderService.getOrder(orderId)
        return ResponseEntity(response, HttpStatus.OK)
    }

    @PutMapping("/api/v1/order/{orderId}")
    fun updateOrder(@PathVariable("orderId") orderId: UUID, @RequestBody request: OrderUpdateRequest): ResponseEntity<Unit> {
        orderService.updateOrder(orderId, request)
        return ResponseEntity(HttpStatus.ACCEPTED)
    }

}

@RestControllerAdvice
class DebugExceptionHandler {

    @ExceptionHandler(Exception::class)
    fun handleAllExceptions(ex: Exception): ResponseEntity<Map<String, String?>> {
        // Принудительно печатаем весь стек-трейс в логи пода
        ex.printStackTrace()

        // Возвращаем реальную причину прямо в HTTP ответе
        return ResponseEntity.badRequest().body(mapOf(
            "error_type" to ex.javaClass.simpleName,
            "message" to ex.message
        ))
    }
}