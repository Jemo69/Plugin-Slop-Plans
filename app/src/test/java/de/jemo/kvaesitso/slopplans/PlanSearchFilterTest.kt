package de.jemo.kvaesitso.slopplans

import de.jemo.kvaesitso.slopplans.api.Plan
import de.jemo.kvaesitso.slopplans.api.PlanSearch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The client-side filter is now the *fallback* path — it only runs against a server
 * without `GET /api/plans/search` — so it has to keep behaving exactly like it did when
 * it was the only path: same fields, same AND-of-terms semantics, same ordering.
 */
class PlanSearchFilterTest {

    private fun plan(
        id: String,
        title: String? = null,
        slug: String? = null,
        description: String? = null,
        frontmatterDescription: String? = null,
        archived: Boolean = false,
        pinned: Boolean = false,
        updatedAt: String? = null,
    ) = Plan(
        id = id,
        title = title,
        slug = slug,
        description = description,
        frontmatter = frontmatterDescription?.let { mapOf("description" to it) },
        isArchived = archived,
        isPinned = pinned,
        updatedAt = updatedAt,
    )

    private val plans = listOf(
        plan("1", title = "Berlin weekend", slug = "berlin-weekend", description = "Museumsinsel", updatedAt = "2026-01-01"),
        plan("2", title = "Lisbon food", slug = "lisbon-food", description = "Pastéis de nata", updatedAt = "2026-03-01"),
        plan("3", title = "Berlin secret spots", slug = "berlin-secret", pinned = true, updatedAt = "2026-02-01"),
        plan("4", title = "Berlin 2024 (archived)", slug = "berlin-2024", archived = true, updatedAt = "2026-04-01"),
        plan("5", title = "Nordics", slug = "nordics", frontmatterDescription = "Oslo and Stockholm", updatedAt = "2026-05-01"),
    )

    @Test
    fun `matches the title case-insensitively`() {
        val hits = PlanSearch.filter(plans, "BERLIN")

        assertEquals(listOf("3", "1"), hits.map { it.id })
    }

    @Test
    fun `matches the slug`() {
        assertEquals(listOf("2"), PlanSearch.filter(plans, "lisbon-food").map { it.id })
    }

    @Test
    fun `matches the flat description from the search endpoint`() {
        assertEquals(listOf("2"), PlanSearch.filter(plans, "nata").map { it.id })
    }

    @Test
    fun `matches a description carried in the frontmatter`() {
        assertEquals(listOf("5"), PlanSearch.filter(plans, "oslo").map { it.id })
    }

    @Test
    fun `ANDs the terms like the server does`() {
        // "berlin" matches four plans, "secret" only one of them.
        assertEquals(listOf("3"), PlanSearch.filter(plans, "berlin secret").map { it.id })
        // Every term must appear somewhere, not all in the same field.
        assertEquals(listOf("5"), PlanSearch.filter(plans, "nordics oslo").map { it.id })
        assertEquals(emptyList<String>(), PlanSearch.filter(plans, "berlin lisbon").map { it.id })
    }

    @Test
    fun `hides archived plans by default`() {
        assertEquals(false, PlanSearch.filter(plans, "berlin").any { it.id == "4" })
    }

    @Test
    fun `shows archived plans when the user asks for them`() {
        val hits = PlanSearch.filter(plans, "berlin", PlanSearch.Options(includeArchived = true))

        // Pinned first, then newest updatedAt: the archived plan is the newest of the three.
        assertEquals(listOf("3", "4", "1"), hits.map { it.id })
    }

    @Test
    fun `an empty query lists pinned plans first, then most recently updated`() {
        val hits = PlanSearch.filter(plans, "")

        // Pinned first regardless of date, then newest updatedAt.
        assertEquals(listOf("3", "5", "2", "1"), hits.map { it.id })
    }

    @Test
    fun `an empty query can be told to show nothing`() {
        val hits = PlanSearch.filter(plans, "", PlanSearch.Options(blankQueryShowsPinned = false))

        assertEquals(emptyList<String>(), hits.map { it.id })
    }

    @Test
    fun `an empty query is capped so the launcher stays fast`() {
        val many = (1..25).map {
            plan(it.toString(), title = "Plan $it", updatedAt = "2026-01-%02dT00:00:00".format(it))
        }

        assertEquals(PlanSearch.BLANK_QUERY_LIMIT, PlanSearch.defaultResults(many).size)
    }

    @Test
    fun `ignores surrounding whitespace and repeated spaces`() {
        assertEquals(listOf("3", "1"), PlanSearch.filter(plans, "   berlin  ").map { it.id })
    }
}