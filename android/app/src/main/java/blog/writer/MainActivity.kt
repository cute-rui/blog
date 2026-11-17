package blog.writer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.data.repo.Appearance
import blog.writer.data.repo.AppearanceRepository
import blog.writer.ui.nav.WriterNav
import blog.writer.ui.theme.WritingTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearanceModel: AppearanceViewModel = hiltViewModel()
            val appearance by appearanceModel.appearance.collectAsState()
            WritingTheme(useDynamicColor = appearance.dynamicColor) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    WriterNav(
                        appearance = appearance,
                        onSecureScreen = { secure ->
                            if (secure) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        },
                    )
                }
            }
        }
    }
}

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    repository: AppearanceRepository,
) : ViewModel() {
    val appearance = repository.appearance.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Appearance(),
    )
}
