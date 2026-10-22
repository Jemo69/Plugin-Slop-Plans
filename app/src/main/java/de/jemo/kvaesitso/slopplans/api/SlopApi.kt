package de.jemo.kvaesitso.slopplans.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format of the Slop plan service.
 *
 * These mirror the FastAPI response models in `routes/auth.py`, `routes/projects.py`
 * and `routes/plans.py` one to one. Every field is nullable with a default: the
 * launcher must never crash because a plan is missing an optional field.
 *
 * Endpoint -> model:
 *   POST /api/auth/login          -> AuthResponse
 *   GET  /api/projects            -> ProjectListResponse
 *   GET  /api/plans/search        -> PlanSearchResponse
 *   GET  /api/plans               -> PlanListResponse
 *   GET  /api/plans/{id}          -> Plan
 */

/** `POST /api/auth/login` and `POST /api/auth/register`. */
@Serializable
data class AuthResponse(
    /**
     * The long-lived token the client sends as `Authorization: Bearer <api_key>`.
     * Generated server-side as `plan_<token_urlsafe(32)>`. This is the only secret
     * the plugin ever stores - the password is used once and then forgotten.
     */
    @SerialName("api_key") val apiKey: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("has_password") val hasPassword: Boolean? = null,
)

/** `GET /api/projects`. */
@Serializable
data class ProjectListResponse(
    val projects: List<Project> = emptyList(),
)

@Serializable
data class Project(
    /** UUID. The service rejects anything that is not a UUID. */
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerialName("is_pinned") val isPinned: Boolean? = null,
    @SerialName("is_archived") val isArchived: Boolean? = null,
    /** ISO-8601 timestamp, e.g. `2026-01-31T09:12:44.123456`. */
    @SerialName("created_at") val createdAt: String? = null,
)

/** `GET /api/plans` and `GET /api/plans/{id}`. */
@Serializable
data class PlanListResponse(
    val plans: List<Plan> = emptyList(),
)

/** `GET /api/plans/search` and `GET /api/plans`. */
@Serializable
data class PlanSearchResponse(
    val plans: List<Plan> = emptyList(),
    /** Number of matches found before the server applied `limit`. */
    val total: Int = 0,
    val query: String = "",
)

@Serializable
data class Plan(
    /** UUID. */
    val id: String? = null,
    /** UUID of the owning project. */
    @SerialName("project_id") val projectId: String? = null,
    val title: String? = null,
    /**
     * URL-safe identifier, unique across all plans. This is what `/p/{slug}` and
     * `/plan/{slug}` are addressed by.
     */
    val slug: String? = null,
    /** Server-relative link, e.g. `/p/my-plan`. The host is ours to supply. */
    val url: String? = null,
    /**
     * The whole HTML document. Only `GET /api/plans/{id}` returns this — the search
     * endpoint omits it and reports `sizeBytes` instead.
     */
    @SerialName("html_content") val htmlContent: String? = null,
    /** Free-form metadata. `description` is the key the service itself reads. */
    val frontmatter: Map<String, String>? = null,
    /**
     * Flattened `frontmatter["description"]`, sent by the search endpoint. Falls back
     * to the frontmatter value when a full plan is returned instead.
     */
    val description: String? = null,
    /**
     * Length of the HTML document, computed server-side so the body itself never has
     * to be downloaded.
     */
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
    @SerialName("is_archived") val isArchived: Boolean? = null,
    @SerialName("is_pinned") val isPinned: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** Human-readable summary line, whichever endpoint produced this plan. */
    val summary: String?
        get() = description?.takeIf { it.isNotBlank() }
            ?: frontmatter?.get("description")?.takeIf { it.isNotBlank() }

    /** Server-reported size, falling back to the body when we happen to have it. */
    val sizeOrNull: Long?
        get() = sizeBytes ?: htmlContent?.length?.toLong()
}