package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals

class ReservationExpirationTest : IntegrationTest() {

    @Autowired
    private lateinit var expirer: ReservationExpirer

    private val pastTtl = Duration.ofMinutes(10).plusSeconds(1)

    @Test
    fun `sweeper expires elapsed holds and returns seats`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId, quantity = 3).json["id"].asString()
        reserve(eventId, quantity = 2)
        assertEquals(5, availableOf(eventId))

        clock.advance(pastTtl)
        assertEquals(2, expirer.expireElapsedHolds())

        assertEquals(10, availableOf(eventId))
        assertEquals("EXPIRED", api.get("/reservations/$id").json["status"].asString())
        assertEquals(0, expirer.expireElapsedHolds(), "second sweep must be a no-op")
    }

    @Test
    fun `holds still within ttl are untouched`() {
        val eventId = createEvent(capacity = 10)
        reserve(eventId, quantity = 3)

        clock.advance(Duration.ofMinutes(9))
        assertEquals(0, expirer.expireElapsedHolds())
        assertEquals(7, availableOf(eventId))
    }

    @Test
    fun `elapsed hold reads as expired even before the sweeper runs`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId).json["id"].asString()

        clock.advance(pastTtl)
        assertEquals("EXPIRED", api.get("/reservations/$id").json["status"].asString())
    }

    @Test
    fun `elapsed hold cannot be confirmed or cancelled`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId, quantity = 2).json["id"].asString()

        clock.advance(pastTtl)
        val confirm = api.post("/reservations/$id/confirm")
        assertEquals(409, confirm.status)
        assertEquals("RESERVATION_EXPIRED", confirm.json["code"].asString())
        assertEquals(409, api.delete("/reservations/$id").status)

        expirer.expireElapsedHolds()
        assertEquals(10, availableOf(eventId), "seats returned exactly once, by the sweeper")
    }

    @Test
    fun `confirmed reservations never expire`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId, quantity = 2).json["id"].asString()
        api.post("/reservations/$id/confirm")

        clock.advance(pastTtl)
        assertEquals(0, expirer.expireElapsedHolds())
        assertEquals("CONFIRMED", api.get("/reservations/$id").json["status"].asString())
        assertEquals(8, availableOf(eventId))
    }
}
