package br.com.ticketbrasil.shared

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * RFC 9457 problem body. A typed class instead of Spring's ProblemDetail on purpose:
 * ProblemDetail serializes its extra properties through a Map any-getter, and under a
 * concurrent cold start Jackson 3.1 intermittently failed to resolve that map's key
 * serializer (NPE -> HTTP 500, seen in the flash-sale load test). Plain fields avoid it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String,
    val code: String,
    val errors: List<FieldViolation>? = null,
    val requested: Int? = null,
    val available: Int? = null,
)

data class FieldViolation(val field: String, val message: String)

/**
 * Translates every failure into an `application/problem+json` body with a stable
 * `code`. Unexpected errors are logged and returned without internals.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun handleDomain(e: DomainException): ResponseEntity<ApiError> {
        val (status, code) = when (e) {
            is InvalidRequestException -> HttpStatus.BAD_REQUEST to "INVALID_REQUEST"
            is EventNotFoundException -> HttpStatus.NOT_FOUND to "EVENT_NOT_FOUND"
            is ReservationNotFoundException -> HttpStatus.NOT_FOUND to "RESERVATION_NOT_FOUND"
            is InsufficientTicketsException -> HttpStatus.CONFLICT to "INSUFFICIENT_TICKETS"
            is EventAlreadyStartedException -> HttpStatus.CONFLICT to "EVENT_ALREADY_STARTED"
            is ReservationExpiredException -> HttpStatus.CONFLICT to "RESERVATION_EXPIRED"
            is InvalidReservationStateException -> HttpStatus.CONFLICT to "INVALID_RESERVATION_STATE"
            is IdempotencyKeyReusedException -> HttpStatus.UNPROCESSABLE_CONTENT to "IDEMPOTENCY_KEY_REUSED"
        }
        val error = error(status, code, e.message)
        return respond(
            if (e is InsufficientTicketsException) error.copy(requested = e.requested, available = e.available) else error,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val violations = e.bindingResult.fieldErrors.map { FieldViolation(it.field, it.defaultMessage ?: "invalid") }
        return respond(
            error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body has invalid fields").copy(errors = violations),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) =
        respond(error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException) =
        respond(error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '${e.name}' has an invalid value"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException) =
        respond(error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(e: HttpRequestMethodNotSupportedException) =
        respond(error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.message))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaType(e: HttpMediaTypeNotSupportedException) =
        respond(error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", e.message))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled error", e)
        return respond(error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error"))
    }

    private fun error(status: HttpStatus, code: String, detail: String?) =
        ApiError(title = status.reasonPhrase, status = status.value(), detail = detail ?: status.reasonPhrase, code = code)

    private fun respond(error: ApiError): ResponseEntity<ApiError> =
        ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(error)
}
