package ru.ilyubarskiy.mai.rover_service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RoverServiceApplication

fun main(args: Array<String>) {
	runApplication<RoverServiceApplication>(*args)
}
