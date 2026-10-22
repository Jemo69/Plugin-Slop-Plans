package de.jemo.kvaesitso.slopplans

import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A live check of the plugin's own HTTP client against a running server.
 *
 * The unit tests exercise [SlopApiClient] against Ktor's MockEngine, which proves the
 * request and response handling but not that the server agrees. This runs the real
 * client against a real server, so it catches what mocks cannot: a field the server
 * renamed, a route that moved, an auth header the server stopped accepting.
 *
 * It is deliberately not part of the normal suite - it needs a server and a network,
 * and a test that fails because a laptop is asleep is worse than no test. Run it by
 * hand:
 *
 *   # terminal 1 - a server with a schema
 *   cd backend
 *   DATABASE_URL=/tmp/slop-live.db .venv/bin/python -m uvicorn main:app --port 8010
 *
 *   # terminal 2
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveServerCheck*' \
 *       -Dslop.live.url=http://127.0.0.1:8010
 */
class LiveServerCheck {

    // Blank means "not configured": the Gradle build forwards an empty string when no
    // server was given, and an empty URL would fail the check instead of skipping it.
    private val serverUrl: String? = System.getProperty("slop.live.url")?.takeIf { it.isNotBlank() }

    @Test
    fun `the plugin client and the server agree`() {
        assumeTrue("set -Dslop.live.url=<server> to run the live check", serverUrl != null)
        val base = serverUrl!!.trimEnd('/')

        val report = runBlocking {
            val email = "livecheck-${System.currentTimeMillis()}@slop-example.com"
            val password = "slop-live-check-password"

            // 1. Register, so the account has an API key. Written with plain HTTP
            //    because the plugin client is read-only by design.
            val registered = postJson(
                "$base/api/auth/register",
                """{"email":"$email","password":"$password"}""",
                null,
            )
            val key = registered["api_key"] as? String ?: ""
            assertTrue("server did not return an api_key: $registered", key.isNotBlank())

            // 2. A project, so plans have somewhere to live.
            val project = postJson(
                "$base/api/projects",
                """{"name":"Live check"}""",
                key,
            )
            val projectId = project["id"] as? String ?: ""
            assertTrue("no project id: $project", projectId.isNotBlank())

            // 3. Two plans, one of them findable only by its description.
            postJson(
                "$base/api/plans",
                """{"project_id":"$projectId","title":"Berlin weekend",
                   "html_content":"<html><body>abc</body></html>",
                   "frontmatter":{"description":"Museumsinsel"}}""",
                key,
            )
            postJson(
                "$base/api/plans",
                """{"project_id":"$projectId","title":"Lisbon food walk",
                   "html_content":"<html><body>def</body></html>",
                   "frontmatter":{"description":"Pasteis de nata"}}""",
                key,
            )

            // From here on, everything goes through the plugin's own client.
            val client = SlopApiClient(baseUrl = base, apiKey = { key })
            try {
                val report = StringBuilder()
                report.appendLine("server:  $base")

                // 4. The health probe the settings screen uses.
                val probe = client.probe()
                assertTrue("health probe failed: ${probe.reason} ${probe.detail}", probe.reachable)
                report.appendLine("health probe: reachable")

                // 5. The credential check the launcher status uses.
                assertTrue("verifyCredentials() failed", client.verifyCredentials())
                report.appendLine("credentials: accepted")

                // 6. Search finds the plan by title.
                val byTitle = client.searchPlans(query = "berlin")
                assertEquals("search by title", 1, byTitle.plans.size)
                assertEquals("Berlin weekend", byTitle.plans.first().title)
                report.appendLine("search by title: '${byTitle.plans.first().title}'")

                // 7. Search finds the other plan by its description only.
                val byDescription = client.searchPlans(query = "pasteis")
                assertEquals("search by description", 1, byDescription.plans.size)
                assertEquals("Lisbon food walk", byDescription.plans.first().title)
                report.appendLine("search by description: '${byDescription.plans.first().title}'")

                // 8. Terms are ANDed, not ORed.
                val both = client.searchPlans(query = "berlin pasteis")
                assertTrue("terms should be ANDed, got ${both.plans.size}", both.plans.isEmpty())
                report.appendLine("multi-term search: ANDed, no false positives")

                // 9. Nonsense matches nothing.
                assertTrue(client.searchPlans(query = "zzzz-nothing-zzzz").plans.isEmpty())
                report.appendLine("nonsense query: no matches")

                // 10. The response carries metadata and no document body.
                val all = client.searchPlans(query = "")
                assertEquals("both plans listed", 2, all.plans.size)
                all.plans.forEach { plan ->
                    assertTrue("plan without an id", !plan.id.isNullOrBlank())
                    assertTrue("plan without a slug", !plan.slug.isNullOrBlank())
                    assertTrue("plan without size_bytes", plan.sizeBytes != null)
                    assertTrue("html_content leaked into the response", plan.htmlContent == null)
                }
                report.appendLine("all plans: ${all.plans.size}, metadata only, no html_content")
                report.appendLine("sizes: " + all.plans.joinToString { "${it.title}=${it.sizeBytes}" })

                report.appendLine("OK - the plugin client and the server agree")
                report.toString()
            } finally {
                client.close()
            }
        }
        println(report)
    }

    // -- tiny HTTP helper, only for the setup writes ------------------------

    private fun postJson(
        url: String,
        body: String,
        apiKey: String?,
    ): Map<String, Any?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/json")
        apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        val payload = body.toByteArray()
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it: OutputStream -> it.write(payload) }

        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        assertTrue("POST $url -> $code $text", code in 200..299)
        return parseJson(text)
    }

    private fun parseJson(text: String): Map<String, Any?> {
        // Deliberately minimal: the live check only needs flat string fields.
        val out = mutableMapOf<String, Any?>()
        Regex("\"([a-z_]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).forEach {
            out[it.groupValues[1]] = it.groupValues[2]
        }
        return out
    }
}
