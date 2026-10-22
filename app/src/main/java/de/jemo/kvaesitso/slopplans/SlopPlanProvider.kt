package de.jemo.kvaesitso.slopplans

import android.content.Intent
import android.util.Log
import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import de.jemo.kvaesitso.slopplans.api.SlopAuthException
import de.jemo.kvaesitso.slopplans.api.SlopEndpointMissingException
import de.jemo.kvaesitso.slopplans.api.DemoData
import de.jemo.kvaesitso.slopplans.api.Plan
import de.jemo.kvaesitso.slopplans.api.PlanSearch
import de.jemo.kvaesitso.slopplans.api.Project
import de.jemo.kvaesitso.slopplans.api.toFile
import de.mm20.launcher2.plugin.config.QueryPluginConfig
import de.mm20.launcher2.plugin.config.StorageStrategy
import de.mm20.launcher2.sdk.PluginState
import de.mm20.launcher2.sdk.base.GetParams
import de.mm20.launcher2.sdk.base.SearchParams
import de.mm20.launcher2.sdk.files.File
import de.mm20.launcher2.sdk.files.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * File search plugin for the Slop plan service.
 *
 * The launcher calls this as a content provider: `search()` while the user types, and
 * `get()` when it re-resolves a stored result. URI parsing, cursors, cancellation and
 * the permission check are all handled by the SDK base class.
 *
 * Plans are stored and served by the backend as HTML pages addressed by a slug, so the
 * natural result type is a file whose URI points at the served page. Tapping a result
 * opens that plan in the browser.
 *
 * Search runs on the server (`GET /api/plans/search`), which returns metadata only —
 * no `html_content`. That matters: the list route ships the whole document for every
 * plan, which is megabytes on every keystroke. Servers that predate the search route
 * fall back to the list route plus [PlanSearch]; see [serverHasSearchRoute].
 */
class SlopPlanProvider : FileProvider(
    QueryPluginConfig(
        // StoreReference keeps only the plan id in the launcher's database, so a plan
        // renamed or archived on the server is reflected the next time it is opened.
        // The service never reuses a UUID, so a stored id cannot point at the wrong plan.
        storageStrategy = StorageStrategy.StoreReference,
    )
) {

    private val cache = PlanCache()
    private var client: SlopApiClient? = null
    private var clientUrl: String? = null

    /**
     * Whether this server is known to answer `GET /api/plans/search`.
     *
     * A 400 or 404 on that route means the deployment predates the endpoint (FastAPI
     * matches the literal path against `/api/plans/{plan_id}` and rejects "search" as
     * a non-UUID). One round trip proves it, so the answer is cached for the life of
     * the provider and the launcher does not pay the failure on every keystroke.
     *
     * @Volatile because the launcher calls this provider from several coroutines.
     */
    @Volatile
    private var serverHasSearchRoute = true

    override fun onCreate(): Boolean {
        // The launcher created us, so a context exists; nothing to set up yet because
        // the server URL only arrives from the user at runtime.
        return super.onCreate()
    }

    /**
     * A search must never throw into the launcher's content provider — an empty list
     * with a log line is strictly better than a crashed query.
     */
    override suspend fun search(query: String, params: SearchParams): List<File> =
        try {
            searchInternal(query, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Search failed, falling back to the cached snapshot", e)
            emptyList()
        }

    private suspend fun searchInternal(query: String, params: SearchParams): List<File> {
        val context = context ?: return emptyList()
        val userData = context.userDataStore.data.first()

        // Demo mode never touches the network. This is what keeps a freshly installed
        // plugin from looking broken: install, enable, type, get results.
        if (userData.demoMode) {
            return demoResults(query, userData)
        }

        val client = clientFor(userData.baseUrl) { userData.apiKey }

        if (params.allowNetwork) {
            if (serverHasSearchRoute) {
                val remote = serverSearch(query, userData, client)
                if (remote != null) return remote
                // The route turned out to be missing; [serverHasSearchRoute] is now
                // false, so this and every later search goes down the fallback path.
            }

            // Refresh the snapshot. On failure fall through to whatever we already had,
            // so a flaky network does not empty the user's search results.
            refresh(client)?.let { cache.replace(it) }
        }

        return cache.search(query, userData, client)
    }

    /**
     * Sample results, shaped exactly like real ones so the launcher cannot tell them
     * apart. A tap opens the service's dashboard, which is a real page whenever a
     * server is configured.
     */
    private fun demoResults(query: String, userData: UserData): List<File> =
        DemoData.filter(query)
            .take(userData.maxResults.coerceIn(1, 50))
            .mapNotNull { plan ->
                plan.toFile(
                    apiClient = SlopApiClient(baseUrl = userData.baseUrl),
                    apiKey = null,
                    projectNames = DemoData.projectNames(),
                    demoUri = "${userData.baseUrl.trimEnd('/')}/dashboard",
                )
            }

    /**
     * Ask the server to do the filtering.
     *
     * @return the results, or `null` if this server has no search route — in which case
     *   the caller falls back to the cached snapshot. `null` also covers a failed
     *   request: a flaky network must not empty the results either.
     */
    private suspend fun serverSearch(
        query: String,
        userData: UserData,
        client: SlopApiClient,
    ): List<File>? = try {
        val response = client.searchPlans(
            query = query,
            limit = userData.maxResults.coerceIn(1, MAX_SERVER_LIMIT),
            includeArchived = userData.includeArchived,
        )
        // Project names label the rows ("Berlin weekend · City trips"). They are
        // account-wide and rarely change, so they are fetched once and then reused
        // instead of on every keystroke.
        ensureProjectNames(client)
        // Deliberately not cached: a search result is a slice of the account, not a
        // snapshot of it, and writing it into PlanCache would replace a complete
        // fallback list with whatever the last keystroke happened to match.
        response.plans.mapNotNull { it.toFile(client, userData.apiKey, cache.projectNames()) }
    } catch (e: SlopEndpointMissingException) {
        serverHasSearchRoute = false
        Log.i(TAG, "Server has no /api/plans/search, using client-side filtering", e)
        null
    } catch (e: SlopAuthException) {
        // The key expired or was revoked. The next status check asks the user to sign
        // in again; here we simply stop producing fresh results.
        Log.w(TAG, "API key rejected", e)
        null
    } catch (e: Exception) {
        Log.w(TAG, "Server-side search failed, using the cached snapshot", e)
        null
    }

    /** Re-resolve one stored result. StoreReference means the launcher will ask. */
    override suspend fun get(id: String, params: GetParams): File? {
        val context = context ?: return null
        val userData = context.userDataStore.data.first()

        if (userData.demoMode) {
            return demoResults("", userData).firstOrNull { it.id == id }
        }

        val client = clientFor(userData.baseUrl) { userData.apiKey }

        val plan = try {
            client.getPlan(id).also { cache.upsert(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh plan $id, falling back to the cached copy", e)
            cache.find(id)
        } ?: return null

        return plan.toFile(client, userData.apiKey, cache.projectNames())
    }

    override suspend fun getPluginState(): PluginState {
        val context = context ?: return PluginState.Ready()
        val userData = context.userDataStore.data.first()

        if (userData.demoMode) {
            return PluginState.Ready(context.getString(R.string.plugin_state_demo))
        }

        if (userData.apiKey.isNullOrBlank()) {
            return PluginState.SetupRequired(
                setupActivity = Intent(context, SettingsActivity::class.java),
                message = context.getString(R.string.plugin_state_no_account),
            )
        }

        if (!clientFor(userData.baseUrl) { userData.apiKey }.verifyCredentials()) {
            return PluginState.SetupRequired(
                setupActivity = Intent(context, SettingsActivity::class.java),
                message = context.getString(R.string.plugin_state_unreachable),
            )
        }

        return PluginState.Ready(
            context.getString(
                R.string.plugin_state_ready,
                userData.email ?: context.getString(R.string.plugin_state_ready_no_email),
            )
        )
    }

    /** Projects first, then plans: project names label the results. */
    private suspend fun refresh(client: SlopApiClient): List<Plan>? = try {
        cache.replaceProjects(client.listProjects().projects)
        client.listPlans().plans
    } catch (e: SlopAuthException) {
        // The key expired or was revoked. The next status check asks the user to sign
        // in again; here we simply stop producing fresh results.
        Log.w(TAG, "API key rejected", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Could not load plans", e)
        null
    }

    /** One-off: the server-side search path never calls `listProjects()` itself. */
    private suspend fun ensureProjectNames(client: SlopApiClient) {
        if (cache.hasProjectNames()) return
        try {
            cache.replaceProjects(client.listProjects().projects)
        } catch (e: Exception) {
            // Purely cosmetic: without names the rows just show the plan title.
            Log.w(TAG, "Could not load project names", e)
        }
    }

    /**
     * One client per server URL. The API key is read through a lambda on every request,
     * so a sign-in from the settings screen takes effect without recreating anything.
     */
    private fun clientFor(baseUrl: String, apiKey: suspend () -> String?): SlopApiClient {
        val existing = client
        if (existing != null && clientUrl == baseUrl) return existing
        existing?.close()
        return SlopApiClient(baseUrl = baseUrl, apiKey = apiKey).also {
            client = it
            clientUrl = baseUrl
            // A different server is a different deployment; it may well have the route.
            serverHasSearchRoute = true
        }
    }

    companion object {
        private const val TAG = "SlopPlanProvider"

        /** The service clamps `limit` to 1..200; mirror it so the two paths agree. */
        private const val MAX_SERVER_LIMIT = 200
    }
}

/**
 * In-memory snapshot of the account, so results keep working without network and stay
 * consistent between the search and refresh paths.
 *
 * It is only filled from `GET /api/plans`, i.e. the fallback path. When the server has
 * the search route the provider does not need a local copy of every plan — which is the
 * whole point of moving search server-side — so [search] answers straight from the
 * response instead.
 */
class PlanCache {
    private var plans: List<Plan>? = null
    private val projects = mutableMapOf<String, String>()

    fun replace(plans: List<Plan>) {
        this.plans = plans
    }

    fun replaceProjects(projects: List<Project>) {
        this.projects.clear()
        projects.forEach { project ->
            val id = project.id ?: return@forEach
            val name = project.name ?: return@forEach
            this.projects[id] = name
        }
    }

    fun projectNames(): Map<String, String> = projects.toMap()

    /** True once at least one project name is known, so the fetch happens at most once. */
    fun hasProjectNames(): Boolean = projects.isNotEmpty()

    fun upsert(plan: Plan) {
        val current = plans.orEmpty()
        plans = if (current.any { it.id == plan.id }) {
            current.map { if (it.id == plan.id) plan else it }
        } else {
            current + plan
        }
    }

    fun find(id: String): Plan? = plans?.firstOrNull { it.id == id }

    fun search(query: String, userData: UserData, client: SlopApiClient): List<File> {
        val all = plans ?: return emptyList()
        val options = PlanSearch.Options(includeArchived = userData.includeArchived)
        return PlanSearch
            .filter(all, query, options)
            .take(userData.maxResults.coerceIn(1, 50))
            .mapNotNull { it.toFile(client, userData.apiKey, projectNames()) }
    }
}