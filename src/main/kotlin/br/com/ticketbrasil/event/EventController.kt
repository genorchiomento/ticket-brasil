package br.com.ticketbrasil.event

import br.com.ticketbrasil.config.TicketProperties
import br.com.ticketbrasil.shared.Idempotency
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

data class CreateEventRequest(
    @field:NotBlank @field:Size(max = 200)
    val name: String?,
    @field:NotNull
    val startsAt: Instant?,
    @field:NotNull @field:Positive
    val capacity: Int?,
)

data class EventResponse(
    val id: UUID,
    val name: String,
    val startsAt: Instant,
    val capacity: Int,
    val available: Int,
    val soldOut: Boolean,
) {
    companion object {
        fun from(event: Event) = EventResponse(
            id = event.id,
            name = event.name,
            startsAt = event.startsAt,
            capacity = event.capacity,
            available = event.available,
            soldOut = event.soldOut,
        )
    }
}

@RestController
@RequestMapping("/events")
class EventController(
    private val service: EventService,
    private val properties: TicketProperties,
) {

    @PostMapping
    fun create(
        @RequestHeader(Idempotency.HEADER, required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateEventRequest,
    ): ResponseEntity<EventResponse> {
        val key = Idempotency.validate(idempotencyKey, required = false)
        val result = service.create(
            CreateEventCommand(request.name!!, request.startsAt!!, request.capacity!!),
            key,
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/events/${result.value.id}"))
            .header(Idempotency.REPLAYED_HEADER, result.replayed.toString())
            .body(EventResponse.from(result.value))
    }

    @GetMapping("/{id}")
    fun availability(@PathVariable id: UUID): ResponseEntity<EventResponse> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(properties.availabilityCacheTtl))
            .body(EventResponse.from(service.getAvailability(id)))
}
