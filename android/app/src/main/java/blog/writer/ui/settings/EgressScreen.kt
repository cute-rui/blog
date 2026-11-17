package blog.writer.ui.settings
import blog.writer.copy.Copy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.data.repo.EgressSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EgressViewModel @Inject constructor(
    private val settings: EgressSettingsRepository,
) : ViewModel() {
    var domain by mutableStateOf("")
    var token by mutableStateOf("")
    var last4 by mutableStateOf<String?>(null)
    var message by mutableStateOf("")

    init {
        viewModelScope.launch {
            domain = settings.domain().orEmpty()
            last4 = settings.tokenLast4()
        }
    }

    fun save() {
        viewModelScope.launch {
            val result = runCatching { settings.save(domain, token.ifBlank { null }) }
            result.fold(
                onSuccess = {
                    message = Copy["saved"]
                    last4 = settings.tokenLast4()
                    token = ""
                },
                onFailure = { error ->
                    message = when {
                        error.message?.contains("16") == true || error.message?.contains("whitespace") == true ->
                            Copy["egress_token_rules"]
                        error.message?.contains("SRV") == true -> error.message ?: Copy["srv_domain_invalid"]
                        else -> error.message ?: Copy["save_failed"]
                    }
                },
            )
        }
    }
}

@Composable
fun EgressScreen(onBack: () -> Unit, model: EgressViewModel = hiltViewModel()) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack) { Text(Copy["back"]) }
            Text(Copy["row_egress"], style = MaterialTheme.typography.headlineSmall)
            Text(
                Copy["egress_note"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = model.domain,
                onValueChange = { model.domain = it },
                label = { Text(Copy["srv_domain"]) },
                supportingText = { Text(Copy["srv_domain_hint"]) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it },
                label = { Text(if (model.last4 != null) Copy.format("egress_token_last4", "last4" to model.last4) else Copy["egress_token"]) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (model.message.isNotBlank()) Text(model.message)
            Button(onClick = model::save, enabled = model.domain.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(Copy["save"])
            }
        }
    }
}
