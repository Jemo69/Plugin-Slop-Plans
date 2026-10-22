package de.jemo.kvaesitso.slopplans

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.jemo.kvaesitso.slopplans.api.FailureReason
import de.jemo.kvaesitso.slopplans.api.SlopApiClient
import de.jemo.kvaesitso.slopplans.api.SlopAuthException
import kotlinx.coroutines.launch

/**
 * Plugin settings: which server, and who you are.
 *
 * The important rule here is what is NOT stored. The password is used once against
 * `POST /api/auth/login` and then discarded — only the returned API key goes into
 * DataStore. The plugin never holds the credentials again, so a leaked backup cannot be
 * turned back into a working login.
 */
class SettingsActivity : AppCompatActivity() {

    private var serverUrl by mutableStateOf(SlopApiClient.DEFAULT_BASE_URL)
    private var serverEdited by mutableStateOf(false)
    private var email by mutableStateOf("")
    private var password by mutableStateOf("")
    private var includeArchived by mutableStateOf(false)
    private var demoMode by mutableStateOf(true)
    private var signedInAs by mutableStateOf<String?>(null)
    private var state by mutableStateOf<SignInState>(SignInState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val stored by userDataStore.data.collectAsState(initial = null)
            LaunchedEffect(stored) {
                stored?.let { data ->
                    if (!serverEdited) serverUrl = data.baseUrl
                    includeArchived = data.includeArchived
                    demoMode = data.demoMode
                    email = data.email.orEmpty()
                    signedInAs = data.apiKey
                        ?.takeIf { it.isNotBlank() }
                        ?.let { data.email ?: getString(R.string.plugin_state_ready_no_email) }
                }
            }

            MaterialTheme(theme()) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ServerSection(
                            serverUrl = serverUrl,
                            reachable = state is SignInState.Reachable,
                            onServerUrlChange = {
                                serverUrl = it.trim()
                                serverEdited = true
                            },
                            onTestConnection = { testConnection() },
                        )

                        HorizontalDivider()

                        SignInSection(
                            email = email,
                            password = password,
                            signedInAs = signedInAs,
                            state = state,
                            onEmailChange = {
                                email = it.trim()
                                state = SignInState.Idle
                            },
                            onPasswordChange = {
                                password = it
                                state = SignInState.Idle
                            },
                            onSignIn = { signIn() },
                            onSignOutRequest = { signOut() },
                        )

                        HorizontalDivider()

                        OptionsSection(
                            demoMode = demoMode,
                            onDemoModeChange = { value ->
                                demoMode = value
                                lifecycleScope.launch {
                                    userDataStore.updateData { it.copy(demoMode = value) }
                                }
                            },
                            includeArchived = includeArchived,
                            onIncludeArchivedChange = { value ->
                                includeArchived = value
                                lifecycleScope.launch {
                                    userDataStore.updateData { it.copy(includeArchived = value) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * `GET /health` is unauthenticated, so it isolates "bad URL or no server" from
     * "bad API key".
     *
     * The failure reason is kept rather than collapsed into a boolean: "cannot reach
     * the server" for a wrong port, an unresolvable host, a rejected certificate and a
     * blocked plain-http request are four different problems with four different fixes,
     * and telling them apart from the phone is otherwise guesswork.
     */
    private fun testConnection() {
        val baseUrl = serverUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            state = SignInState.Unreachable(FailureReason.UNKNOWN, "No server address")
            return
        }
        lifecycleScope.launch {
            state = SignInState.Working
            val client = SlopApiClient(baseUrl = baseUrl)
            val probe = try {
                client.probe()
            } finally {
                client.close()
            }
            state = if (probe.reachable) {
                SignInState.Reachable
            } else {
                SignInState.Unreachable(
                    reason = probe.reason ?: FailureReason.UNKNOWN,
                    detail = probe.detail,
                )
            }
        }
    }

    private fun signIn() {
        val baseUrl = serverUrl.trim().trimEnd('/')
        if (baseUrl.isBlank() || email.isBlank() || password.isBlank()) {
            state = SignInState.InvalidCredentials
            return
        }
        lifecycleScope.launch {
            state = SignInState.Working
            val client = SlopApiClient(baseUrl = baseUrl)
            try {
                val auth = client.login(email, password)
                val apiKey = auth.apiKey?.takeIf { it.isNotBlank() }
                if (apiKey == null) {
                    state = SignInState.Error
                    return@launch
                }
                val account = auth.email ?: email
                // The server address is saved in the same transaction, so a later
                // search cannot run against a different host than the one on screen.
                userDataStore.updateData {
                    it.copy(baseUrl = baseUrl, apiKey = apiKey, email = account)
                }
                serverUrl = baseUrl
                email = account
                password = ""
                signedInAs = account
                state = SignInState.SignedIn(account)
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed", e)
                state = if (e is SlopAuthException) SignInState.InvalidCredentials
                else SignInState.Error
            } finally {
                client.close()
            }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            userDataStore.updateData { it.copy(apiKey = null, email = null) }
            signedInAs = null
            password = ""
            state = SignInState.Idle
        }
    }

    private fun theme() = if (isNightMode()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(this)
        else darkColorScheme()
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(this)
        else lightColorScheme()
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "SlopSettings"
    }
}

/**
 * Turn a probe failure into a sentence the user can act on, rather than one generic
 * "cannot reach the server" for six different problems.
 */
private fun FailureReason.stringRes(): Int = when (this) {
    FailureReason.DNS -> R.string.settings_error_dns
    FailureReason.TLS -> R.string.settings_error_tls
    FailureReason.REFUSED -> R.string.settings_error_refused
    FailureReason.TIMEOUT -> R.string.settings_error_timeout
    FailureReason.UNEXPECTED_STATUS -> R.string.settings_error_status
    FailureReason.CLEARTEXT_BLOCKED -> R.string.settings_error_cleartext
    FailureReason.UNKNOWN -> R.string.settings_error_unknown
}

/** The states the sign-in area can be in. */
private sealed interface SignInState {
    data object Idle : SignInState
    data object Working : SignInState
    data object Reachable : SignInState
    data class Unreachable(val reason: FailureReason, val detail: String?) : SignInState
    data object InvalidCredentials : SignInState
    data object Error : SignInState
    data class SignedIn(val email: String) : SignInState
}

@Composable
private fun ServerSection(
    serverUrl: String,
    reachable: Boolean,
    onServerUrlChange: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(R.string.settings_step_server, 1),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.settings_server_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text(stringResource(R.string.settings_server_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            supportingText = { Text(stringResource(R.string.settings_server_help)) },
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTestConnection) {
                Text(stringResource(R.string.settings_test_connection))
            }
            if (reachable) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SignInSection(
    email: String,
    password: String,
    signedInAs: String?,
    state: SignInState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOutRequest: () -> Unit,
) {
    var confirmingSignOut by remember { mutableStateOf(false) }
    val signedIn = signedInAs != null

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(R.string.settings_step_signin, 2),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.settings_signin_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (signedIn) {
            Spacer(Modifier.height(16.dp))
            FeedbackLine(
                stringResource(R.string.settings_signed_in, signedInAs),
                success = true,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmingSignOut = true }) {
                Text(stringResource(R.string.settings_signout))
            }
        } else {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                value = email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.settings_email_label)) },
                singleLine = true,
                enabled = state !is SignInState.Working,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.settings_password_label)) },
                singleLine = true,
                enabled = state !is SignInState.Working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )

            when (state) {
                SignInState.InvalidCredentials -> FeedbackLine(
                    stringResource(R.string.settings_error_invalid_credentials),
                    success = false,
                )

                is SignInState.Unreachable -> FeedbackLine(
                    stringResource(state.reason.stringRes(), state.detail.orEmpty()),
                    success = false,
                )

                SignInState.Error -> FeedbackLine(
                    stringResource(R.string.settings_error_generic),
                    success = false,
                )

                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    enabled = state !is SignInState.Working && email.isNotBlank() && password.isNotBlank(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    onClick = onSignIn,
                ) {
                    if (state is SignInState.Working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Login,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                    Text(
                        text = stringResource(
                            if (state is SignInState.Working) {
                                R.string.settings_signing_in
                            } else {
                                R.string.settings_signin_button
                            }
                        ),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.settings_signout)) },
            text = { Text(stringResource(R.string.settings_signout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    onSignOutRequest()
                }) {
                    Text(stringResource(R.string.settings_signout_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun OptionsSection(
    demoMode: Boolean,
    onDemoModeChange: (Boolean) -> Unit,
    includeArchived: Boolean,
    onIncludeArchivedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(R.string.settings_options),
            style = MaterialTheme.typography.titleLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_demo_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_demo_mode_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = demoMode, onCheckedChange = onDemoModeChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_include_archived),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_include_archived_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = includeArchived, onCheckedChange = onIncludeArchivedChange)
        }
    }
}

@Composable
private fun FeedbackLine(text: String, success: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}