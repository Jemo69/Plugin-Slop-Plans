package de.jemo.kvaesitso.slopplans.api

import io.ktor.client.HttpClient
import android.util.Log
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Server said no, or the server said something unexpected. */
open class SlopApiException(message: String) : Exception(message)

/**
 * The stored API key is missing, rejected (401) or the account no longer exists.
 * The plugin turns this into `PluginState.SetupRequired` so the user is prompted to
 * sign in again instead of seeing an empty result list.
 */
class SlopAuthException(message: String) : SlopApiException(message)

/**
 * The server does not have the route we asked for — either a 404, or FastAPI matching
 * the literal path against `/api/plans/{plan_id}` and rejecting it as a non-UUID with
 * a 400.
 *
 * The plugin treats this as "this deployment predates the search endpoint" and falls
 * back to fetching the plan list and filtering on device.
 */
class SlopEndpointMissingException(message: String) : SlopApiException(message)

/**
 * Client for the Slop plan service.
 *
 * Android-free by design: the base URL and credentials are injected, so this class is
 * unit-testable on the JVM with Ktor's MockEngine.
 *
 * Routes the launcher needs:
 *   POST /api/auth/login     - email + password in, API key out
 *   GET  /api/plans/search   - the search itself: metadata only, no `html_content`
 *   GET  /api/plans          - the plan list, used only when the server has no search
 *   GET  /api/plans/{id}     - one plan, for refreshing a stored result
 *   GET  /api/projects       - project names, for labelling results
 *   GET  /health             - connectivity check used by the settings screen
 *
 * The service exposes more (plans CRUD, publish/pin/archive, version history and
 * diffs). See docs/API.md for the full surface and why the plugin does not use it.
 */
class SlopApiClient(
    engine: HttpClientEngine = OkHttp.create(),
    baseUrl: String,
    private val apiKey: suspend () -> String? = { null },
) {
    companion object {
        /**
         * 10.0.2.2 is the host machine as seen from the Android emulator, which makes
         * this the most useful default for local development. Override it in the
         * plugin settings with your LAN IP or your deployed URL.
         */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
        private const val TAG = "SlopApi"
    }

    private val root = baseUrl.trimEnd('/')
    private val client = HttpClient(engine) { configure() }

    /**
     * Sign in with email and password and get back the long-lived API key.
     *
     * The service answers 404 when the email is unknown, 401 when the password is
     * wrong and 400 when the account has no password set - all three mean "those
     * credentials do not work", so they collapse into one exception. The password is
     * never persisted anywhere.
     */
    suspend fun login(email: String, password: String): AuthResponse {
        val response = client.post("$root/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", JsonPrimitive(email))
                    put("password", JsonPrimitive(password))
                }
            )
        }
        return response.authenticatedBody()
    }

    /**
     * `GET /health` — unauthenticated, so it isolates "bad URL or no server" from
     * "bad API key".
     *
     * Unlike [health], this keeps the failure cause and puts it in logcat. A plugin that
     * reports every network problem as the same sentence is impossible to debug from a
     * phone.
     */
    suspend fun probe(): Probe = try {
        val status = client.get("$root/health").status
        if (status.isSuccess()) {
            Probe(reachable = true)
        } else {
            Log.w(TAG, "Health check to $root returned HTTP ${status.value}")
            Probe(
                reachable = false,
                reason = FailureReason.UNEXPECTED_STATUS,
                detail = "HTTP ${status.value} from $root",
            )
        }
    } catch (e: Exception) {
        val reason = e.classify()
        Log.e(TAG, "Health check to $root failed ($reason): ${e.message}", e)
        Probe(reachable = false, reason = reason, detail = e.message ?: reason.name)
    }

    /** [probe] as a plain boolean, for callers that only need yes/no. */
    suspend fun health(): Boolean = probe().reachable

    /** All plans the signed-in user can see. The service returns them pinned-first. */
    suspend fun listPlans(projectId: String? = null): PlanListResponse {
        val response = client.get("$root/api/plans") {
            authHeader()
            projectId?.let { parameter("project_id", it) }
        }
        return response.authenticatedBody()
    }

    /**
     * Server-side search. This is the path a normal search takes: it returns metadata
     * only, never `html_content`, so a keystroke costs a few hundred bytes per match
     * instead of a full document per plan.
     *
     * @throws SlopEndpointMissingException if the server has no `/api/plans/search`.
     */
    suspend fun searchPlans(
        query: String,
        limit: Int = 20,
        includeArchived: Boolean = false,
        projectId: String? = null,
    ): PlanSearchResponse {
        val response = client.get("$root/api/plans/search") {
            authHeader()
            parameter("q", query)
            parameter("limit", limit.coerceIn(1, 200))
            parameter("include_archived", includeArchived)
            projectId?.let { parameter("project_id", it) }
        }
        return response.authenticatedBody(routeIsOptional = true)
    }

    /** One plan, used to refresh a result the launcher stored earlier. */
    suspend fun getPlan(planId: String): Plan {
        val response = client.get("$root/api/plans/${planId.urlEncode()}")
        return response.authenticatedBody()
    }

    /** Project names, so results can say which project a plan belongs to. */
    suspend fun listProjects(): ProjectListResponse {
        val response = client.get("$root/api/projects")
        return response.authenticatedBody()
    }

    /**
     * Cheap check that both the URL and the key work: `GET /api/plans` on a bad key
     * returns 401, so one request answers "is this installation usable?".
     */
    suspend fun verifyCredentials(): Boolean = try {
        listPlans()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Credential check against $root failed (${e.classify()}): ${e.message}")
        false
    }

    /**
     * Where a result opens when the user taps it.
     *
     * Public plans go to `/p/{slug}`. Private plans have no anonymous route - the
     * service only serves them to a signed-in viewer, identified by `?token=`. This
     * mirrors what the web dashboard does, but it does put the key into a URL, which
     * the browser may keep in its history. The plugin only builds these locally and
     * hands them to the browser through an ACTION_VIEW intent.
     */
    fun openUrl(plan: Plan, apiKey: String?): String? {
        val slug = plan.slug?.takeIf { it.isNotBlank() } ?: return null
        return if (plan.isPublic == true) {
            "$root/p/${slug.urlEncode()}"
        } else if (!apiKey.isNullOrBlank()) {
            "$root/plan/${slug.urlEncode()}?token=${apiKey.urlEncode()}"
        } else {
            null
        }
    }

    fun close() = client.close()

    private suspend fun HttpRequestBuilder.authHeader() {
        val token = apiKey()
        if (!token.isNullOrBlank()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    /**
     * Turns the service's `{"detail": "..."}` error shape into typed exceptions, so a
     * dead key can be told apart from a dead server.
     */
    private suspend inline fun <reified T> HttpResponse.authenticatedBody(
        routeIsOptional: Boolean = false,
    ): T {
        if (status.isSuccess()) return body()

        val detail = runCatching { bodyAsText() }.getOrDefault("").extractDetail()
        when (status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                throw SlopAuthException(detail.ifBlank { "Invalid or expired API key" })

            // Only the search route may be absent. Everywhere else a 404 means "this
            // plan does not exist" and must stay an ordinary API error, so the caller
            // does not mistake a deleted plan for an outdated server.
            HttpStatusCode.BadRequest, HttpStatusCode.NotFound ->
                if (routeIsOptional) {
                    // 400 here means FastAPI matched the literal path against
                    // /api/plans/{plan_id} and rejected "search" as a non-UUID, i.e.
                    // this deployment predates the search endpoint. Same story as 404.
                    throw SlopEndpointMissingException(detail.ifBlank { "Endpoint not available" })
                } else {
                    throw SlopApiException(detail.ifBlank { "Not found" })
                }

            else -> throw SlopApiException("HTTP ${status.value}: $detail")
        }
    }
}

/** Why a connection attempt failed, in terms the user can actually act on. */
enum class FailureReason {
    /** The hostname does not resolve. Wrong domain, or no DNS on that network. */
    DNS,

    /** The TLS handshake was rejected - self-signed or otherwise untrusted cert. */
    TLS,

    /** Nothing is listening on that host and port. The usual "server not started". */
    REFUSED,

    /** Connected but the server never answered within the timeout. */
    TIMEOUT,

    /** The server answered, with a status the plugin does not expect. */
    UNEXPECTED_STATUS,

    /** Plain http:// on targetSdk 28+, so Android blocked it before it left the device. */
    CLEARTEXT_BLOCKED,

    UNKNOWN,
}

/** The outcome of a connectivity probe, with enough detail to explain a failure. */
data class Probe(
    val reachable: Boolean,
    val reason: FailureReason? = null,
    val detail: String? = null,
)

/**
 * Walks the cause chain looking for something actionable.
 *
 * Ktor wraps engine exceptions, so the useful type is rarely the outermost one, and
 * "unable to resolve host" arrives from the Android resolver as an UnknownHostException
 * buried several levels down.
 */
internal fun Throwable.classify(): FailureReason {
    val chain = generateSequence(this) { it.cause }.take(12).toList()
    fun anyFound(predicate: (Throwable) -> Boolean) = chain.any(predicate)

    return when {
        anyFound { it is java.net.UnknownHostException && it.message?.contains("CLEARTEXT") == true } ->
            FailureReason.CLEARTEXT_BLOCKED

        anyFound { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } ->
            FailureReason.TLS

        anyFound { it is java.net.UnknownHostException } -> FailureReason.DNS

        anyFound {
            it is java.net.SocketTimeoutException ||
                it is io.ktor.client.plugins.HttpRequestTimeoutException ||
                it is java.util.concurrent.TimeoutException
        } -> FailureReason.TIMEOUT

        anyFound { it is java.net.ConnectException } -> FailureReason.REFUSED

        else -> FailureReason.UNKNOWN
    }
}

/** Pulls `detail` out of FastAPI's `{"detail": "..."}` body, falling back to raw text. */
internal fun String.extractDetail(): String {
    val match = Regex("\"detail\"\\s*:\\s*\"(.*?)\"").find(this) ?: return take(200)
    return match.groupValues[1]
}

/** Path and query segments must be percent-encoded; slugs come from user titles. */
internal fun String.urlEncode(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

internal fun HttpClientConfig<*>.configure() {
    expectSuccess = false
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 20_000
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, "KvaesitsoSlopPlans")
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }
}