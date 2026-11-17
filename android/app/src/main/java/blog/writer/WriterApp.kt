package blog.writer

import android.app.Application
import blog.writer.copy.Copy
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WriterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Copy.load(assets.open("app-copy.json").bufferedReader().use { it.readText() })
    }
}
