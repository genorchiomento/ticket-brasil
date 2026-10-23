package br.com.ticketbrasil.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Boots the real application against a real PostgreSQL (Testcontainers) and talks to it
 * over HTTP, so tests exercise the same transaction and locking behavior as production.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "ticket.expiration.enabled=false",
        "ticket.availability-cache-ttl=PT0S",
        "ticket.reservation-ttl=PT10M",
        "spring.datasource.hikari.maximum-pool-size=30",
    ],
)
@Import(TestInfraConfig::class)
abstract class IntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var clock: MutableClock

    @Autowired
    protected lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var mapper: JsonMapper

    protected val api: ApiClient by lazy { ApiClient("http://localhost:$port", mapper) }

    @BeforeEach
    fun resetState() {
        jdbc.sql("TRUNCATE reservations, events").update()
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    protected fun createEvent(capacity: Int, startsIn: Duration = Duration.ofDays(30)): UUID {
        val response = api.post(
            "/events",
            mapOf("name" to "Show", "startsAt" to clock.instant().plus(startsIn).toString(), "capacity" to capacity),
        )
        check(response.status == 201) { "Event creation failed: ${response.body}" }
        return UUID.fromString(response.json["id"].asString())
    }

    protected fun reserve(eventId: UUID, quantity: Int = 1, key: String = UUID.randomUUID().toString(), customerId: String = "customer-1") =
        api.post(
            "/events/$eventId/reservations",
            mapOf("customerId" to customerId, "quantity" to quantity),
            headers = mapOf("Idempotency-Key" to key),
        )

    protected fun availableOf(eventId: UUID): Int =
        jdbc.sql("SELECT available FROM events WHERE id = :id").param("id", eventId).query(Int::class.java).single()
}
