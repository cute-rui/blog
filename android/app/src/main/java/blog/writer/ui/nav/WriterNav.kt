package blog.writer.ui.nav

import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import blog.writer.data.repo.Appearance
import blog.writer.ui.editor.EditorScreen
import blog.writer.ui.list.PostListScreen
import blog.writer.ui.meta.FrontMatterScreen
import blog.writer.ui.preview.PreviewScreen
import blog.writer.ui.settings.GithubScreen
import blog.writer.ui.settings.LlmEditScreen
import blog.writer.ui.settings.LlmHomeScreen
import blog.writer.ui.settings.SettingsScreen
import blog.writer.ui.search.SearchEditScreen
import blog.writer.ui.search.SearchListScreen

fun encodePath(path: String): String =
    Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun decodePath(value: String): String =
    String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

@Composable
fun WriterNav(
    appearance: Appearance,
    onSecureScreen: (Boolean) -> Unit,
) {
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route.orEmpty()
            onSecureScreen(route.startsWith("llm") || route.startsWith("github") || route.startsWith("search"))
        }
    }
    NavHost(navController = nav, startDestination = "posts") {
        composable("posts") {
            PostListScreen(
                onOpen = { path -> nav.navigate("editor/${encodePath(path)}") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "editor/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) {
            EditorScreen(
                appearance = appearance,
                onBack = { nav.popBackStack() },
                onInfo = { path -> nav.navigate("meta/${encodePath(path)}") },
                onPreview = { path -> nav.navigate("preview/${encodePath(path)}") },
                onSettings = { nav.navigate("llm") },
            )
        }
        composable(
            "meta/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) {
            FrontMatterScreen(onBack = { nav.popBackStack() })
        }
        composable(
            "preview/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) {
            PreviewScreen(onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onLlm = { nav.navigate("llm") },
                onGithub = { nav.navigate("github") },
                onSearch = { nav.navigate("search") },
            )
        }
        composable("llm") {
            LlmHomeScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("llm/edit/${Uri.encode(id)}") },
                onCreate = { nav.navigate("llm/edit/new") },
            )
        }
        composable(
            "llm/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            LlmEditScreen(onBack = { nav.popBackStack() })
        }
        composable("github") {
            GithubScreen(onBack = { nav.popBackStack() })
        }
        composable("search") {
            SearchListScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("search/edit/${Uri.encode(id)}") },
                onCreate = { kind -> nav.navigate("search/edit/new-$kind") },
            )
        }
        composable(
            "search/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            SearchEditScreen(onBack = { nav.popBackStack() }, onOpenLlm = { nav.navigate("llm") })
        }
    }
}
