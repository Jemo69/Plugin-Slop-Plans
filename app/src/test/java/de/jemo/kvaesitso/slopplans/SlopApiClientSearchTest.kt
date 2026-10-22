package de.jemo.kvaesitso.slopplans

import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import de.jemo.kvaesitso.slopplans.api.SlopApiException
import de.jemo.kvaesitso.slopplans.api.SlopAuthException
import de.jemo.kvaesitso.slopplans.api.SlopEndpointMissingException
import de.jemo.kvaesitso.slopplans.api.MIME_HTML
import de.jemo.kvaesitso.slopplans.api.PlanSearchResponse
import de.jemo.kvaesitso.slopplans.api.toFile
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE_URL = MockApi.BASE_URL
private const val API_KEY = MockApi.API_KEY

/**
 * The search route the launcher actually queries: `GET /api/plans/search`.
 *
 * The point of the route is that its items carry `size_bytes` and never `html_content`,
 * so a keystroke costs a few hundred bytes per match instead of a full document per plan.
 */
class SlopApiClientSearchTest {

    @Test
    fun `sends q, limit and include_archived with the bearer token`() = runTest {
        val (client, requests) = MockApi.client { json(EMPTY_RESPONSE) }

        client.searchPlans(query = "berlin", limit = 7, includeArchived = true)

        val request = requests.single()
        assertEquals("/api/plans/search", request.url.encodedPath)
        assertEquals("berlin", request.url.parameters["q"])
        assertEquals("7", request.url.parameters["limit"])
        assertEquals("true", request.url.parameters["include_archived"])
        assertEquals("Bearer $API_KEY", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `defaults to limit 20 and archived excluded`() = runTest {
        val (client, requests) = MockApi.client { json(EMPTY_RESPONSE) }

        client.searchPlans(query = "")

        val request = requests.single()
        assertEquals("", request.url.parameters["q"])
        assertEquals("20", request.url.parameters["limit"])
        assertEquals("false", request.url.parameters["include_archived"])
        // Scoping to one project is optional; it must be absent, not empty.
        assertNull(request.url.parameters["project_id"])
    }

    @Test
    fun `scopes the search to a project when asked`() = runTest {
        val (client, requests) = MockApi.client { json(EMPTY_RESPONSE) }

        client.searchPlans(query = "berlin", projectId = "3c1a-project")

        assertEquals("3c1a-project", requests.single().url.parameters["project_id"])
    }

    @Test
    fun `maps a successful response into launcher file results`() = runTest {
        val (client, _) = MockApi.client { json(SEARCH_RESPONSE) }

        val response: PlanSearchResponse = client.searchPlans("berlin")
        val projects = mapOf("project-berlin" to "City trips")
        val files = response.plans.mapNotNull { it.toFile(client, API_KEY, projects) }

        assertEquals(2, files.size)

        val public = files[0]
        assertEquals("11111111-1111-1111-1111-111111111111", public.id)
        assertEquals("Berlin weekend · City trips", public.displayName)
        assertEquals(MIME_HTML, public.mimeType)
        assertEquals("$BASE_URL/p/berlin-weekend", public.uri.toString())
        // size_bytes stands in for the document the plugin deliberately never downloads.
        assertEquals(48210L, public.size)

        val private = files[1]
        assertEquals("$BASE_URL/plan/berlin-secret?token=$API_KEY", private.uri.toString())
    }

    @Test
    fun `reports the pre-limit match count and the echoed query`() = runTest {
        val (client, _) = MockApi.client { json(SEARCH_RESPONSE) }

        val response = client.searchPlans("berlin")

        assertEquals(3, response.total)
        assertEquals("berlin", response.query)
    }

    @Test
    fun `a 404 means this server has no search route`() = runTest {
        val (client, _) = MockApi.client { status(404, "Not Found") }

        assertTrue(searchFailure(client) is SlopEndpointMissingException)
    }

    @Test
    fun `a 400 also means this server has no search route`() = runTest {
        // FastAPI matches the literal path against /api/plans/{plan_id} and rejects
        // "search" as a non-UUID, which is exactly how an un-updated server answers.
        val (client, _) = MockApi.client { status(400, "value is not a valid UUID") }

        assertTrue(searchFailure(client) is SlopEndpointMissingException)
    }

    @Test
    fun `a 401 is still an auth problem, not a missing endpoint`() = runTest {
        val (client, _) = MockApi.client { status(401, "Not authenticated") }

        assertTrue(searchFailure(client) is SlopAuthException)
    }

    @Test
    fun `an unexpected status is a plain api error`() = runTest {
        val (client, _) = MockApi.client { status(500, "boom") }

        val error = searchFailure(client)

        assertFalse(error is SlopEndpointMissingException)
        assertEquals("HTTP 500: boom", error.message)
    }

    @Test
    fun `a 404 on a single plan is still just a missing plan`() = runTest {
        // getPlan() must not inherit the optional-route mapping: a deleted plan is not
        // an outdated server, and treating it as one would disable search for good.
        val (client, _) = MockApi.client { status(404, "Plan not found") }

        val error = try {
            client.getPlan("11111111-2222-3333-4444-555555555555")
            throw AssertionError("expected getPlan to fail")
        } catch (e: SlopApiException) {
            e
        }

        assertFalse(error is SlopEndpointMissingException)
        assertFalse(error is SlopAuthException)
        assertEquals("Plan not found", error.message)
    }

    /** Runs a search that is expected to fail and hands back what it threw. */
    private suspend fun searchFailure(client: SlopApiClient): SlopApiException = try {
        client.searchPlans("berlin")
        throw AssertionError("expected searchPlans to fail")
    } catch (e: SlopApiException) {
        e
    }

    companion object {
        private const val EMPTY_RESPONSE = """{"plans": [], "total": 0, "query": ""}"""

        /** Exactly the documented shape: no `html_content`, `size_bytes` instead. */
        private val SEARCH_RESPONSE = """
            {
              "plans": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "project_id": "project-berlin",
                  "title": "Berlin weekend",
                  "slug": "berlin-weekend",
                  "url": "/p/berlin-weekend",
                  "description": "Three days, Museumsinsel",
                  "is_public": true,
                  "is_archived": false,
                  "is_pinned": false,
                  "created_at": "2026-01-31T09:12:44",
                  "updated_at": "2026-02-02T18:03:11",
                  "size_bytes": 48210
                },
                {
                  "id": "22222222-2222-2222-2222-222222222222",
                  "project_id": "project-berlin",
                  "title": "Berlin secret spots",
                  "slug": "berlin-secret",
                  "url": "/p/berlin-secret",
                  "is_public": false,
                  "is_archived": false,
                  "is_pinned": true,
                  "created_at": "2026-01-05T08:00:00",
                  "updated_at": "2026-01-06T08:00:00",
                  "size_bytes": 990
                }
              ],
              "total": 3,
              "query": "berlin"
            }
        """.trimIndent()
    }
}