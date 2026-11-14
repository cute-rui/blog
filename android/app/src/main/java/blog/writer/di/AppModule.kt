package blog.writer.di

import android.content.Context
import androidx.room.Room
import blog.writer.crypto.AesGcmCredentialCipher
import blog.writer.crypto.CredentialCipher
import blog.writer.data.crypto.AndroidKeystoreKeys
import blog.writer.data.db.WriterDao
import blog.writer.data.db.WriterDatabase
import blog.writer.data.files.LocalContentStore
import blog.writer.ai.LlmClients
import blog.writer.render.StubWechatMarkdownRenderer
import blog.writer.render.WechatMarkdownRenderer
import blog.writer.sync.SyncEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WriterDatabase =
        Room.databaseBuilder(context, WriterDatabase::class.java, "writer.db").build()

    @Provides
    fun dao(database: WriterDatabase): WriterDao = database.dao()

    @Provides
    @Singleton
    fun files(@ApplicationContext context: Context): LocalContentStore =
        LocalContentStore(File(context.filesDir, "content"))

    @Provides
    @Singleton
    fun cipher(): CredentialCipher = AesGcmCredentialCipher(AndroidKeystoreKeys.getOrCreate())

    @Provides
    @Singleton
    fun wechatRenderer(): WechatMarkdownRenderer = StubWechatMarkdownRenderer()

    @Provides
    @Singleton
    fun syncEngine(): SyncEngine = SyncEngine()

    @Provides
    @Singleton
    fun llmClients(http: OkHttpClient): LlmClients = LlmClients(http)
}
