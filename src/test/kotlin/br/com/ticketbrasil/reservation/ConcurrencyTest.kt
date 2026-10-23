package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.support.ApiResponse
import br.com.ticketbrasil.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Hammers the API from many threads at once. Every request goes through its own HTTP
 * connection and DB transaction, which is exactly what N API instances behind a load
 * balancer look like to PostgreSQL.
 */
class ConcurrencyTest : IntegrationTest() {

    @Autowired
    private lateinit var expirer: ReservationExpirer

    private fun <T> concurrently(times: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(64)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until times).map { i -> pool.submit<T> { start.await(); action(i) } }
            start.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun soldTickets(eventId: UUID): Int =
        jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM reservations WHERE event_id = :id AND status IN ('PENDING','CONFIRMED')")
            .param("id", eventId)
            .query(Int::class.java)
            .single()

    @Test
    fun `flash sale never oversells`() {
        val capacity = 50
        val eventId = createEvent(capacity)

        val responses = concurrently(400) { i -> reserve(eventId, quantity = 1, customerId = "customer-$i") }

        assertEquals(capacity, responses.count { it.status == 201 })
        assertEquals(400 - capacity, responses.count { it.status == 409 })
        assertEquals(0, availableOf(eventId))
        assertEquals(capacity, soldTickets(eventId))
    }

    @Test
    fun `mixed quantities never exceed capacity`() {
        val eventId = createEvent(capacity = 97)

        val responses = concurrently(200) { i -> reserve(eventId, quantity = 1 + i % 4, customerId = "c-$i") }

        val sold = responses.filter { it.status == 201 }.sumOf { it.json["quantity"].asInt() }
        assertEquals(sold, soldTickets(eventId))
        assertEquals(97 - sold, availableOf(eventId))
        assertEquals(true, availableOf(eventId) < 4, "remaining stock should be smaller than the largest request")
    }

    @Test
    fun `concurrent retries with the same key create exactly one reservation`() {
        val eventId = createEvent(capacity = 100)

        val responses: List<ApiResponse> = concurrently(30) { reserve(eventId, quantity = 2, key = "same-key") }

        assertEquals(setOf(201), responses.map { it.status }.toSet())
        assertEquals(1, responses.map { it.json["id"].asString() }.toSet().size)
        assertEquals(29, responses.count { it.header("Idempotent-Replayed") == "true" })
        assertEquals(98, availableOf(eventId))
    }

    @Test
    fun `concurrent cancels release seats only once`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId, quantity = 5).json["id"].asString()

        val responses = concurrently(20) { api.delete("/reservations/$id") }

        assertEquals(setOf(200), responses.map { it.status }.toSet())
        assertEquals(10, availableOf(eventId))
    }

    @Test
    fun `concurrent sweepers on several instances release each hold exactly once`() {
        val eventId = createEvent(capacity = 300)
        repeat(300) { reserve(eventId, quantity = 1, customerId = "c-$it") }
        assertEquals(0, availableOf(eventId))

        clock.advance(Duration.ofMinutes(11))
        val expiredPerSweeper = concurrently(6) { expirer.expireElapsedHolds() }

        assertEquals(300, expiredPerSweeper.sum())
        assertEquals(300, availableOf(eventId))
    }

    @Test
    fun `confirm racing the sweeper ends in exactly one consistent outcome`() {
        val eventId = createEvent(capacity = 50)
        val ids = (0 until 50).map { reserve(eventId, customerId = "c-$it").json["id"].asString() }

        // Exactly at the deadline: confirm must lose (hold elapsed), sweeper must win.
        clock.advance(Duration.ofMinutes(10))
        val results = concurrently(ids.size + 2) { i ->
            if (i < ids.size) api.post("/reservations/${ids[i]}/confirm").status else expirer.expireElapsedHolds()
        }

        assertEquals(setOf(409), results.take(ids.size).toSet())
        assertEquals(50, availableOf(eventId))
    }
}
