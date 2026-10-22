package de.jemo.kvaesitso.slopplans

import android.content.Context
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Everything the plugin remembers between launches.
 *
 * Note what is *not* here: the password. It is used once against `/api/auth/login`
 * and the returned API key is what gets stored. The key is also excluded from
 * cloud backup in `backup_rules.xml`, because a backup is a data leak waiting to
 * happen.
 */
@Serializable
data class UserData(
    /** Server origin, e.g. `https://plans.example.com`. No trailing slash. */
    val baseUrl: String = SlopApiClient.DEFAULT_BASE_URL,
    /** `plan_<token>` - the Bearer token every authenticated request sends. */
    val apiKey: String? = null,
    /** Shown in the settings screen and the plugin status line. */
    val email: String? = null,
    /** Archived plans are hidden unless the user asks for them. */
    val includeArchived: Boolean = false,
    /**
     * Serve built-in sample plans instead of talking to a server.
     *
     * On by default so a freshly installed plugin returns results immediately. Turn it
     * off once you have signed in and the server is reachable.
     */
    val demoMode: Boolean = true,
    /** Result limit; keeps the launcher snappy on big accounts. */
    val maxResults: Int = 20,
)

val Context.userDataStore by dataStore("slop.json", UserDataSerializer)

object UserDataSerializer : Serializer<UserData> {
    override val defaultValue: UserData = UserData()

    override suspend fun readFrom(input: InputStream): UserData = try {
        Json.decodeFromStream(input)
    } catch (e: Exception) {
        // A corrupt preferences file must degrade to defaults, not crash the launcher.
        defaultValue
    }

    override suspend fun writeTo(t: UserData, output: OutputStream) {
        Json.encodeToStream(UserData.serializer(), t, output)
    }
}

fun Context.userData(): Flow<UserData> = userDataStore.data.map { it }