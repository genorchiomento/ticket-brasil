package br.com.ticketbrasil.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit

@TestConfiguration(proxyBeanMethods = false)
class TestInfraConfig {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS))
}
