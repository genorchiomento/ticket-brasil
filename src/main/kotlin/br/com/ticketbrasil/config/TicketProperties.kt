package br.com.ticketbrasil.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("ticket")
data class TicketProperties(
    /** How long a PENDING reservation holds seats before it is released. */
    val reservationTtl: Duration = Duration.ofMinutes(10),
    val maxTicketsPerReservation: Int = 10,
    /** Staleness budget for GET /events/{id}; 0 disables the cache. */
    val availabilityCacheTtl: Duration = Duration.ofSeconds(1),
    val expiration: Expiration = Expiration(),
) {
    data class Expiration(
        val enabled: Boolean = true,
        val interval: Duration = Duration.ofSeconds(5),
        val batchSize: Int = 500,
    )
}
