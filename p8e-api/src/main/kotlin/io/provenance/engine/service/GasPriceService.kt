package io.provenance.engine.service

import feign.RequestLine
import io.provenance.p8e.shared.extension.logger
import org.springframework.stereotype.Component
import java.lang.IllegalStateException
import java.time.Duration
import java.time.OffsetDateTime

interface GasPriceService {
    fun getGasPriceNHash(): Double
}

class UrlGasPriceService(val client: UrlGasPriceClient, val fallbackPriceNHash: Double): GasPriceService {
    private val log = logger()

    override fun getGasPriceNHash(): Double = runCatching {
        client.gasPriceDetails().let {
            if (it.gasPriceDenom != "nhash") {
                throw IllegalStateException("Unsupported denom of ${it.gasPriceDenom} received for gas price resolution, required: nhash")
            }

            log.info("Fetched gas price of ${it.gasPrice}${it.gasPriceDenom}")

            it.gasPrice
        }
    }.getOrElse {
        log.warn("Using fallback gas price of ${fallbackPriceNHash}nhash")
        fallbackPriceNHash
    }
}

class ConstantGasPriceService(val gasPrice: Double): GasPriceService {
    override fun getGasPriceNHash(): Double = gasPrice
}

class CachedGasPriceService(val inner: GasPriceService, val cacheDuration: Duration): GasPriceService {
    private var lastFetched: OffsetDateTime = OffsetDateTime.now()
    private var lastFetchedPrice: Double = inner.getGasPriceNHash()

    override fun getGasPriceNHash(): Double {
        if (OffsetDateTime.now().isAfter(lastFetched.plusSeconds(cacheDuration.seconds))) {
            lastFetchedPrice = inner.getGasPriceNHash()
            lastFetched = OffsetDateTime.now()
        }

        return lastFetchedPrice
    }
}

data class GasPriceResponse(val gasPrice: Double, val gasPriceDenom: String)

@Component
interface UrlGasPriceClient {
    @RequestLine("GET /")
    fun gasPriceDetails(): GasPriceResponse
}
