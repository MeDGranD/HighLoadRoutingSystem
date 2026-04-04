package ru.ilyubarskiy.mai.routing_service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class RoutingServiceApplication

fun main(args: Array<String>) {
	runApplication<RoutingServiceApplication>(*args)
}
