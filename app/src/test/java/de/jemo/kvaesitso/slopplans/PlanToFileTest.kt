package de.jemo.kvaesitso.slopplans

import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import de.jemo.kvaesitso.slopplans.api.Plan
import de.jemo.kvaesitso.slopplans.api.toFile
import io.ktor.client.engine.mock.MockEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Plan.toFile` maps a plan onto the launcher's result row.
 *
 * The interesting part is the size: the search endpoint reports `size_bytes` instead of
 * shipping `html_content`, so the mapper has to prefer it. Getting this backwards would
 * show every search result as "0 bytes".
 */
class PlanToFileTest {

    private val client = SlopApiClient(
        engine = MockEngine { error("toFile must not hit the network") },
        baseUrl = "https://plans.example.com",
        apiKey = { "plan_test_key" },
    )

    private fun plan(
        title: String = "Berlin weekend",
        slug: String = "berlin-weekend",
        projectId: String? = "project-berlin",
        html: String? = null,
        sizeBytes: Long? = null,
    ) = Plan(
        id = "11111111-1111-1111-1111-111111111111",
        projectId = projectId,
        title = title,
        slug = slug,
        htmlContent = html,
        sizeBytes = sizeBytes,
        isPublic = true,
    )

    @Test
    fun `prefers the server-reported size over the html length`() {
        val file = plan(html = "<html>${"x".repeat(10_000)}</html>", sizeBytes = 48_210L)
            .toFile(client, "plan_test_key")!!

        assertEquals(48_210L, file.size)
    }

    @Test
    fun `falls back to the html length when the server reported no size`() {
        // The fallback path does hold the document, so its length is the best estimate.
        val html = "<html>${"x".repeat(123)}</html>"
        val file = plan(html = html, sizeBytes = null).toFile(client, "plan_test_key")!!

        assertEquals(html.length.toLong(), file.size)
    }

    @Test
    fun `reports zero when neither a size nor the html is available`() {
        val file = plan(html = null, sizeBytes = null).toFile(client, "plan_test_key")!!

        assertEquals(0L, file.size)
    }

    @Test
    fun `names the row after the plan and its project`() {
        val file = plan().toFile(client, "plan_test_key", mapOf("project-berlin" to "City trips"))!!

        assertEquals("Berlin weekend · City trips", file.displayName)
        assertEquals("Berlin weekend", file.metadata.title)
        assertEquals("City trips", file.metadata.appName)
    }

    @Test
    fun `drops the project suffix when the project is unknown`() {
        val file = plan().toFile(client, "plan_test_key", emptyMap())!!

        assertEquals("Berlin weekend", file.displayName)
        assertEquals("Slop", file.metadata.appName)
    }

    @Test
    fun `drops a private plan with no key to open it with`() {
        val private = plan().copy(isPublic = false)

        assertNull(private.toFile(client, apiKey = null))
    }
}