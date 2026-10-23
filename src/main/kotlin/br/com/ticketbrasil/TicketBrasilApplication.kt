package br.com.ticketbrasil

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TicketBrasilApplication

fun main(args: Array<String>) {
    runApplication<TicketBrasilApplication>(*args)
}
