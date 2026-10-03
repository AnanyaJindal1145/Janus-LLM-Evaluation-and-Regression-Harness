package com.janus.model

/**
 * Thrown by real provider models on an HTTP failure. `isRetryable` classifies the failure for
 * the M5 retry wrapper: 429 (rate limit — the common one on free tiers) and 5xx/timeouts are
 * retryable with backoff; other 4xx (bad request, auth) fail fast — retrying a malformed or
 * unauthorized request just wastes the rate-limit budget.
 */
class ProviderException(
    val statusCode: Int,
    message: String,
) : RuntimeException(message) {
    val isRetryable: Boolean
        get() = statusCode == 429 || statusCode in 500..599 || statusCode == 408
}
