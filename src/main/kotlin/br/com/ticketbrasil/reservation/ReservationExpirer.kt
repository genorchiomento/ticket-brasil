package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.config.TicketProperties
import br.com.ticketbrasil.event.EventRepository
import br.com.ticketbrasil.event.EventService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/** Releases seats held by PENDING reservations whose hold has elapsed. */
@Component
class ReservationExpirer(
    private val reservations: ReservationRepository,
    private val events: EventRepository,
    private val eventService: EventService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val properties: TicketProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Drains all elapsed holds in bounded batches. @return number of reservations expired. */
    fun expireElapsedHolds(): Int {
        val batchSize = properties.expiration.batchSize
        var total = 0
        do {
            val expired = expireBatch(batchSize)
            total += expired
        } while (expired == batchSize)
        if (total > 0) log.info("Expired {} reservation(s)", total)
        return total
    }

    private fun expireBatch(batchSize: Int): Int {
        val elapsed = tx.execute {
            val now = clock.instant()
            val elapsed = reservations.lockElapsedHolds(now, batchSize)
            if (elapsed.isEmpty()) return@execute elapsed

            reservations.markExpired(elapsed.map { it.id }, now)
            // Sorted so concurrent sweepers lock event rows in the same order (no deadlocks).
            elapsed.groupBy({ it.eventId }, { it.quantity })
                .toSortedMap()
                .forEach { (eventId, quantities) -> events.increment(eventId, quantities.sum()) }
            elapsed
        }!!
        elapsed.map { it.eventId }.distinct().forEach(eventService::evictAvailability)
        return elapsed.size
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty("ticket.expiration.enabled", havingValue = "true", matchIfMissing = true)
class ReservationExpirationScheduler(private val expirer: ReservationExpirer) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ticket.expiration.interval:PT5S}")
    fun run() {
        try {
            expirer.expireElapsedHolds()
        } catch (e: Exception) {
            // Never let a transient DB error kill the schedule; the next tick retries.
            log.error("Reservation expiration sweep failed", e)
        }
    }
}
