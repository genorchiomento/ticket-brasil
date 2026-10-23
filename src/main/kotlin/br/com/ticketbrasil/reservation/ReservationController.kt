package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.shared.Idempotency
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreateReservationRequest(
    @field:NotBlank @field:Size(max = 100)
    val customerId: String?,
    @field:NotNull
    val quantity: Int?,
)

data class ReservationResponse(
    val id: UUID,
    val eventId: UUID,
    val customerId: String,
    val quantity: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(reservation: Reservation, now: Instant) = ReservationResponse(
            id = reservation.id,
            eventId = reservation.eventId,
            customerId = reservation.customerId,
            quantity = reservation.quantity,
            status = reservation.effectiveStatusAt(now),
            expiresAt = reservation.expiresAt,
            createdAt = reservation.createdAt,
            updatedAt = reservation.updatedAt,
        )
    }
}

@RestController
class ReservationController(
    private val service: ReservationService,
    private val clock: Clock,
) {

    @PostMapping("/events/{eventId}/reservations")
    fun reserve(
        @PathVariable eventId: UUID,
        @RequestHeader(Idempotency.HEADER, required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateReservationRequest,
    ): ResponseEntity<ReservationResponse> {
        val key = Idempotency.validate(idempotencyKey, required = true)!!
        val result = service.reserve(ReserveCommand(eventId, request.customerId!!, request.quantity!!), key)
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/reservations/${result.value.id}"))
            .header(Idempotency.REPLAYED_HEADER, result.replayed.toString())
            .body(ReservationResponse.from(result.value, clock.instant()))
    }

    @GetMapping("/reservations/{id}")
    fun get(@PathVariable id: UUID): ReservationResponse =
        ReservationResponse.from(service.get(id), clock.instant())

    @DeleteMapping("/reservations/{id}")
    fun cancel(@PathVariable id: UUID): ReservationResponse =
        ReservationResponse.from(service.cancel(id), clock.instant())

    @PostMapping("/reservations/{id}/confirm")
    fun confirm(@PathVariable id: UUID): ReservationResponse =
        ReservationResponse.from(service.confirm(id), clock.instant())
}
