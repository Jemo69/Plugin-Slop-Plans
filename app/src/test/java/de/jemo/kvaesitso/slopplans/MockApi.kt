package de.jemo.kvaesitso.slopplans

import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** Shared plumbing for the Ktor client tests: a MockEngine plus a request log. */
internal object MockApi {

    const val BASE_URL = "https://plans.example.com"
    const val API_KEY = "plan_test_key"

    /** A client whose every request is recorded in [requests] and answered by [handler]. */
    fun client(
        apiKey: String? = API_KEY,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<SlopApiClient, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val client = SlopApiClient(engine = engine, baseUrl = BASE_URL, apiKey = { apiKey })
        return client to requests
    }
}

/** A 200 with a JSON body, as the FastAPI service would send. */
internal fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, jsonHeaders())

/** A FastAPI error, which always carries `{"detail": "..."}`. */
internal fun MockRequestHandleScope.status(code: Int, detail: String? = null): HttpResponseData =
    respond(
        content = detail?.let { """{"detail": "$it"}""" } ?: "",
        status = HttpStatusCode.fromValue(code),
        headers = jsonHeaders(),
    )

private fun jsonHeaders() =
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())