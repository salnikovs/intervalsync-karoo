package icu.intervalsync.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountState
import icu.intervalsync.karoo.account.AccountStore
import icu.intervalsync.karoo.account.IntervalsyncApi
import icu.intervalsync.karoo.core.AthleteParams
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val settings by lazy { SettingsStore(this) }
    private val account by lazy { AccountController(IntervalsyncApi(karooSystem), AccountStore(this), settings) }
    private val karooFtp = MutableStateFlow<Int?>(null)
    private var profileConsumer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                        AccountSection(account)
                        HorizontalDivider()
                        AthleteSection(settings, karooFtp)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect()
        profileConsumer = karooSystem.addConsumer { profile: UserProfile ->
            karooFtp.value = profile.ftp.takeIf { it > 0 }
        }
        lifecycleScope.launch { account.syncIfStale() }
    }

    override fun onStop() {
        account.cancelPairing()
        profileConsumer?.let { karooSystem.removeConsumer(it) }
        profileConsumer = null
        karooSystem.disconnect()
        super.onStop()
    }
}

@Composable
fun AccountSection(account: AccountController) {
    val state by account.state.collectAsState()
    val scope = rememberCoroutineScope()

    when (val current = state) {
        is AccountState.NotPaired, is AccountState.Failed -> {
            Text(
                if (current is AccountState.Failed) current.message else stringResource(R.string.account_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { account.startPairing(scope) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (current is AccountState.Failed) R.string.account_retry else R.string.account_connect))
            }
        }
        is AccountState.Pairing -> {
            Text(stringResource(R.string.account_pairing_open, current.url), style = MaterialTheme.typography.bodyMedium)
            Text(current.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.account_pairing_waiting), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { account.cancelPairing() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_cancel))
            }
        }
        is AccountState.Paired -> {
            Text(
                current.athleteName?.let { stringResource(R.string.account_connected_as, it) } ?: stringResource(R.string.account_connected),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { scope.launch { account.syncAthlete() } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_sync))
            }
            TextButton(onClick = { scope.launch { account.unpair() } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_disconnect))
            }
        }
    }
}

@Composable
fun AthleteSection(settings: SettingsStore, karooFtp: StateFlow<Int?>) {
    val initial = remember { settings.read() }
    val stored by remember { settings.flow() }.collectAsState(initial)
    var cp by remember { mutableStateOf(initial.cpW?.toString().orEmpty()) }
    var wPrimeKj by remember { mutableStateOf(initial.wPrimeJ?.let { formatKj(it) }.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    val ftp by karooFtp.collectAsState()

    Text(stringResource(R.string.settings_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = cp,
        onValueChange = {
            cp = it.filter(Char::isDigit)
            saved = false
        },
        label = { Text(stringResource(R.string.settings_cp)) },
        supportingText = {
            val syncedCp = stored.syncedCpW
            Text(
                when {
                    syncedCp != null -> stringResource(R.string.settings_cp_default_synced, syncedCp)
                    ftp != null -> stringResource(R.string.settings_cp_default_ftp, ftp ?: 0)
                    else -> stringResource(R.string.settings_cp_default_none)
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = wPrimeKj,
        onValueChange = {
            wPrimeKj = it.filter { c -> c.isDigit() || c == '.' }
            saved = false
        },
        label = { Text(stringResource(R.string.settings_w_prime)) },
        supportingText = {
            val syncedWPrime = stored.syncedWPrimeJ
            Text(
                if (syncedWPrime != null) {
                    stringResource(R.string.settings_w_prime_default_synced, formatKj(syncedWPrime))
                } else {
                    stringResource(R.string.settings_w_prime_default, formatKj(AthleteParams.DEFAULT_W_PRIME_J.roundToInt()))
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            settings.write(
                cpW = cp.toIntOrNull()?.takeIf { it > 0 },
                wPrimeJ = wPrimeKj.toDoubleOrNull()?.let { (it * 1000).roundToInt() }?.takeIf { it > 0 },
            )
            saved = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (saved) R.string.settings_saved else R.string.settings_save))
    }
}

private fun formatKj(joules: Int): String = (joules / 1000.0).toString().removeSuffix(".0")
