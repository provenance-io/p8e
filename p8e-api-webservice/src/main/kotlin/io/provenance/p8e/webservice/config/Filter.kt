package io.provenance.p8e.webservice.config

import io.provenance.p8e.shared.extension.logger
import org.springframework.web.filter.AbstractRequestLoggingFilter
import javax.servlet.http.HttpServletRequest

class AppRequestLoggingFilter : AbstractRequestLoggingFilter() {
    private val log = logger()

    override fun shouldLog(request: HttpServletRequest): Boolean = !request.requestURI.contains("/manage/health")

    override fun beforeRequest(request: HttpServletRequest, message: String) {
        request.setAttribute("startTime", System.currentTimeMillis())
    }

    override fun afterRequest(request: HttpServletRequest, message: String) {
        log.info("{} - {} {} took {} ms.",
            request.remoteAddr,
            request.method,
            request.requestURI,
            System.currentTimeMillis() - request.getAttribute("startTime") as Long
        )
    }
}
