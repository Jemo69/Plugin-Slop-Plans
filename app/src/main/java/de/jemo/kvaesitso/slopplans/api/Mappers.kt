package de.jemo.kvaesitso.slopplans.api

import android.net.Uri
import de.mm20.launcher2.sdk.files.File
import de.mm20.launcher2.sdk.files.FileMetadata

/**
 * Plan -> launcher result.
 *
 * The SDK models search results as files, and a plan is exactly that: a document with
 * a stable id, a MIME type and a URI the launcher can open with ACTION_VIEW.
 *
 * The `html_content` never reaches the launcher - a plan is a page, not a download, and
 * tapping a result opens the served page at `/p/{slug}`. So the search endpoint reports
 * `size_bytes` instead of shipping the document, and that is what the result row shows;
 * the fallback path, which does hold the HTML, uses its length instead.
 */
fun Plan.toFile(
    apiClient: SlopApiClient,
    apiKey: String?,
    projectNames: Map<String, String> = emptyMap(),
    demoUri: String? = null,
): File? {
    val planId = id?.takeIf { it.isNotBlank() } ?: return null
    val title = title?.takeIf { it.isNotBlank() } ?: slug ?: return null
    // Demo plans do not exist on the server, so they open the dashboard instead.
    val url = demoUri ?: apiClient.openUrl(this, apiKey) ?: return null

    val project = projectId?.let { projectNames[it] }

    return File(
        id = planId,
        displayName = displayLabel(project),
        mimeType = MIME_HTML,
        size = (sizeOrNull ?: 0L),
        // Informational only - the launcher opens `uri`, not `path`.
        path = "/api/plans/$planId",
        uri = Uri.parse(url),
        isDirectory = false,
        thumbnailUri = null,
        metadata = FileMetadata(
            title = title,
            appName = project ?: "Slop",
        ),
    )
}

/** The plan's own title plus whatever identifies it, for the launcher's one-line row. */
fun Plan.displayLabel(projectName: String?): String =
    displayLabelOf(title?.takeIf { it.isNotBlank() } ?: slug ?: "", projectName)

/**
 * `"Berlin weekend · City trips"` - or just the title when the plan's project is
 * unknown, which is the common case until the project list has been loaded.
 */
internal fun displayLabelOf(title: String, projectName: String?): String =
    if (projectName.isNullOrBlank()) title else "$title · $projectName"

const val MIME_HTML = "text/html"

/**
 * Client-side filtering over an already-downloaded plan list.
 *
 * This is the *fallback* path: it only runs against a server that has no
 * `GET /api/plans/search`, because that route's items are metadata-only and already
 * filtered by the backend. See `SlopPlanProvider.search` for how the two paths are
 * chosen.
 *
 * Matching is on title, slug and the description - the three fields a user would
 * recognise a plan by. Terms are ANDed and case-insensitive, matching the server.
 * Archived plans are hidden unless the user asks for them.
 */
object PlanSearch {

    data class Options(
        val includeArchived: Boolean = false,
        /** Blank query means "show me what matters right now", not "show nothing". */
        val blankQueryShowsPinned: Boolean = true,
    )

    fun filter(
        plans: List<Plan>,
        query: String,
        options: Options = Options(),
    ): List<Plan> {
        val visible = plans.filter { options.includeArchived || it.isArchived != true }
        val needle = query.trim()

        if (needle.isEmpty()) {
            if (!options.blankQueryShowsPinned) return emptyList()
            return visible
                .sortedWith(PINNED_FIRST)
                .take(BLANK_QUERY_LIMIT)
        }

        val terms = needle.lowercase().split(" ").filter { it.isNotBlank() }

        return visible
            .filter { plan -> terms.all { term -> plan.matches(term) } }
            .sortedWith(PINNED_FIRST)
    }

    /**
     * An empty query lists pinned plans first and tops up with recently updated ones,
     * so typing "hit" in the launcher immediately shows what the user pinned last.
     */
    fun defaultResults(plans: List<Plan>, options: Options = Options()): List<Plan> =
        filter(plans, "", options)

    /**
     * `summary` covers both wire shapes: search items carry a flat `description`, full
     * plan responses keep it in the frontmatter.
     */
    private fun Plan.matches(term: String): Boolean =
        title?.lowercase()?.contains(term) == true ||
            slug?.lowercase()?.contains(term) == true ||
            summary?.lowercase()?.contains(term) == true

    private val PINNED_FIRST =
        compareByDescending<Plan> { it.isPinned == true }
            .thenByDescending { it.updatedAt ?: it.createdAt ?: "" }
            .thenBy { it.title?.lowercase() ?: "" }

    const val BLANK_QUERY_LIMIT = 10
}