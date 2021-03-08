package io.provenance.p8e.webservice.config

import com.google.protobuf.GeneratedMessageV3
import io.p8e.util.NotFoundException
import io.p8e.util.new
import io.provenance.p8e.webservice.util.AccessDeniedException
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.lang.reflect.Type
import kotlin.reflect.KClass

@ControllerAdvice(basePackages = ["io.provenance.p8e.webservice.controller"])
class GlobalControllerAdvice : ResponseEntityExceptionHandler() {

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseBody
    fun handleIllegalArgument(e: Throwable): ResponseEntity<*> {
        val errors = listOf(if (e.message != null) e.message!! else "Invalid argument")

        logger.warn("", e)

        return ResponseEntity(ErrorMessage(errors), HttpStatus.BAD_REQUEST)
    }

    override fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException,
                                              headers: HttpHeaders,
                                              status: HttpStatus,
                                              request: WebRequest): ResponseEntity<Any> {
        val errors = ex.bindingResult.fieldErrors.map { "${it.field} has error ${it.defaultMessage}" }

        logger.warn("", ex)

        return ResponseEntity(ErrorMessage(errors), HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(AccessDeniedException::class)
    @ResponseBody
    fun handleAccessDenied(e: Throwable): ResponseEntity<*> {
        val errors = listOf(if (e.message != null) e.message!! else "Access Denied")

        logger.warn("", e)

        return ResponseEntity(ErrorMessage(errors), HttpStatus.FORBIDDEN)
    }

    @ExceptionHandler(OAuthException::class)
    @ResponseBody
    fun handleOAuthError(e: Throwable): ResponseEntity<*> {
        val errors = listOf(if (e.message != null) e.message!! else "OAuth Problem")

        return ResponseEntity(ErrorMessage(errors), HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(NotFoundException::class, NoSuchElementException::class)
    @ResponseBody
    fun handleNotFound(e: Throwable): ResponseEntity<*> {
        val errors = listOf(if (e.message != null) e.message!! else "Not Found")

        //logger.warn("", e)

        return ResponseEntity(ErrorMessage(errors), HttpStatus.NOT_FOUND)
    }

    @ExceptionHandler(IllegalStateException::class, ConflictException::class)
    @ResponseBody
    fun handleConflict(e: Throwable): ResponseEntity<*> {
        val errors = listOf(if (e.message != null) e.message!! else "Conflict")

        logger.warn("", e)

        return ResponseEntity(ErrorMessage(errors), HttpStatus.CONFLICT)
    }

    @ExceptionHandler(Exception::class)
    @ResponseBody
    fun handleException(e: Throwable): ResponseEntity<*> {
        val errors = listOf(if (e.message != null) e.message!! else "Server Error")

        logger.error("", e)

        return ResponseEntity(ErrorMessage(errors), HttpStatus.INTERNAL_SERVER_ERROR)
    }
}

data class ErrorMessage(val errors: List<String>)

class ConflictException(override var message:String): Exception(message)
class OAuthException(override var message:String): Exception(message)

@ControllerAdvice(basePackages = ["io.provenance.p8e.webservice.controller"])
class ProtoEmptyRequestBodyAdviceChain : RequestBodyAdvice {

    fun clazz(type: Type) = try {
        Class.forName(type.typeName).kotlin
    } catch (e: Throwable) {
        null
    }

    override fun supports(param: MethodParameter, type: Type, aClass: Class<out HttpMessageConverter<*>>) =
        clazz(type) != null

    override fun handleEmptyBody(
        body: Any?,
        input: HttpInputMessage,
        param: MethodParameter,
        type: Type,
        aClass: Class<out HttpMessageConverter<*>>
    ) = (clazz(type) as? KClass<GeneratedMessageV3>)?.new()

    override fun beforeBodyRead(
        input: HttpInputMessage,
        param: MethodParameter,
        type: Type,
        aClass: Class<out HttpMessageConverter<*>>
    ) = input

    override fun afterBodyRead(
        body: Any,
        input: HttpInputMessage,
        param: MethodParameter,
        type: Type,
        aClass: Class<out HttpMessageConverter<*>>
    ) = body
}
