package blog.writer.ui.settings

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
                    message = "已保存"
                    last4 = settings.tokenLast4()
                    token = ""
                },
                onFailure = { error ->
                    message = when {
                        error.message?.contains("16") == true || error.message?.contains("whitespace") == true ->
                            "出口令牌至少 16 位，不能有空格"
                        error.message?.contains("SRV") == true -> error.message ?: "SRV 域名不对"
                        else -> error.message ?: "没有保存"
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
            TextButton(onClick = onBack) { Text("返回") }
            Text("微信出口", style = MaterialTheme.typography.headlineSmall)
            Text(
                "手机查询 _wechat._tcp.加上这个域名，再连固定 IP 上的 gRPC。AppID 和 AppSecret 只放在那台 VPS 上，不进这台手机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = model.domain,
                onValueChange = { model.domain = it },
                label = { Text("SRV 域名") },
                supportingText = { Text("例如 example.com，不要填地址和端口") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it },
                label = { Text(if (model.last4 != null) "出口令牌 · 末 4 位 ${model.last4}" else "出口令牌") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (model.message.isNotBlank()) Text(model.message)
            Button(onClick = model::save, enabled = model.domain.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
        }
    }
}
