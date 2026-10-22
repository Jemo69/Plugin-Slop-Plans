package de.jemo.kvaesitso.slopplans.api

import de.mm20.launcher2.sdk.files.File
import de.mm20.launcher2.sdk.files.FileMetadata
import de.mm20.launcher2.sdk.weather.WeatherLocation

/**
 * Built-in sample data.
 *
 * The point of this is that a freshly installed plugin is never dead. Install it,
 * enable it, type in the launcher, and you get results — no account, no server, no
 * network. That matters for two reasons:
 *
 *  - a launcher plugin that silently returns nothing looks broken, and users uninstall
 *    it before ever finding the settings screen,
 *  - it gives you something to verify the installation and the launcher wiring while
 *    you are still sorting out the server address.
 *
 * Demo results open the service's own dashboard, so a tap still lands somewhere real
 * once a server is configured.
 */
object DemoData {

    private data class Demo(
        val title: String,
        val slug: String,
        val description: String,
        val project: String,
        val public: Boolean = true,
    )

    private val DEMOS = listOf(
        Demo(
            title = "Berlin weekend",
            slug = "berlin-weekend",
            description = "Three days around the Museumsinsel, Kreuzberg and the Spree",
            project = "City trips",
        ),
        Demo(
            title = "Lisbon food walk",
            slug = "lisbon-food-walk",
            description = "Pastéis de nata, the old town and a very long lunch",
            project = "City trips",
        ),
        Demo(
            title = "Ski trip planning",
            slug = "ski-trip-planning",
            description = "Lift passes, chalet, and who is bringing what",
            project = "House",
        ),
        Demo(
            title = "Q3 budget review",
            slug = "q3-budget-review",
            description = "Numbers, charts and the one surprise in travel",
            project = "Work",
            public = false,
        ),
        Demo(
            title = "House renovation log",
            slug = "house-renovation-log",
            description = "Before and after, costs, and what we would do differently",
            project = "House",
        ),
        Demo(
            title = "Gift ideas",
            slug = "gift-ideas",
            description = "A running list, not a shortlist",
            project = "House",
        ),
    )

    /**
     * Demo plans, shaped exactly like the ones the server returns, so they travel
     * through the same mapping code and the same result list.
     */
    fun plans(): List<Plan> = DEMOS.map { demo ->
        Plan(
            id = "demo-${demo.slug}",
            projectId = "demo-project-${demo.project.lowercase().replace(' ', '-')}",
            title = demo.title,
            slug = demo.slug,
            url = "/p/${demo.slug}",
            description = demo.description,
            sizeBytes = null,
            isPublic = demo.public,
            isArchived = false,
            isPinned = false,
            createdAt = null,
            updatedAt = null,
        )
    }

    /** Project names for the demo plans, keyed by the ids above. */
    fun projectNames(): Map<String, String> =
        DEMOS.associate { "demo-project-${it.project.lowercase().replace(' ', '-')}" to it.project }

    /**
     * Demo results as launcher results.
     *
     * The URI points at the service's dashboard rather than at a plan that does not
     * exist, so a tap opens something real instead of a 404.
     */
    fun files(baseUrl: String): List<File> {
        val root = baseUrl.trimEnd('/')
        val names = projectNames()
        return plans().mapNotNull { plan ->
            plan.toFile(
                apiClient = SlopApiClient(baseUrl = root),
                apiKey = null,
                projectNames = names,
                demoUri = "$root/dashboard",
            )
        }
    }

    /** Matches the demo titles and descriptions, the same way the server matches. */
    fun filter(query: String): List<Plan> {
        val terms = query.trim().lowercase().split(" ").filter { it.isNotBlank() }
        if (terms.isEmpty()) return plans()
        return plans().filter { plan ->
            val haystack = "${plan.title} ${plan.slug} ${plan.description}".lowercase()
            terms.all { it in haystack }
        }
    }
}
