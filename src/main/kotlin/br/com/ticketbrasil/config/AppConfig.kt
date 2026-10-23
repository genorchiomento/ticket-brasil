package br.com.ticketbrasil.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@Configuration
class AppConfig {

    /**
     * Ticks in microseconds, the resolution of PostgreSQL TIMESTAMPTZ, so an object
     * returned right after a write is identical to the same object read back later
     * (e.g. on an idempotent replay).
     */
    @Bean
    fun clock(): Clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))
}
