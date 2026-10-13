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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import icu.intervalsync.karoo.core.AthleteParams
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val settings by lazy { SettingsStore(this) }
    private val karooFtp = MutableStateFlow<Int?>(null)
    private var profileConsumer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(settings, karooFtp)
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
    }

    override fun onStop() {
        profileConsumer?.let { karooSystem.removeConsumer(it) }
        profileConsumer = null
        karooSystem.disconnect()
        super.onStop()
    }
}

@Composable
fun SettingsScreen(settings: SettingsStore, karooFtp: StateFlow<Int?>) {
    val initial = remember { settings.read() }
    var cp by remember { mutableStateOf(initial.cpW?.toString().orEmpty()) }
    var wPrimeKj by remember { mutableStateOf(initial.wPrimeJ?.let { formatKj(it) }.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    val ftp by karooFtp.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.settings_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = cp,
            onValueChange = {
                cp = it.filter(Char::isDigit)
                saved = false
            },
            label = { Text(stringResource(R.string.settings_cp)) },
            supportingText = {
                Text(ftp?.let { stringResource(R.string.settings_cp_default_ftp, it) } ?: stringResource(R.string.settings_cp_default_none))
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
                Text(stringResource(R.string.settings_w_prime_default, (AthleteParams.DEFAULT_W_PRIME_J / 1000).roundToInt()))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                settings.write(
                    AthleteSettings(
                        cpW = cp.toIntOrNull()?.takeIf { it > 0 },
                        wPrimeJ = wPrimeKj.toDoubleOrNull()?.let { (it * 1000).roundToInt() }?.takeIf { it > 0 },
                    ),
                )
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (saved) R.string.settings_saved else R.string.settings_save))
        }
    }
}

private fun formatKj(joules: Int): String = (joules / 1000.0).toString().removeSuffix(".0")
