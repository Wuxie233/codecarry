package dev.wuxie233.codecarry.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wuxie233.codecarry.data.preferences.SessionListDataStore
import javax.inject.Singleton

private val Context.sessionListDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "session_list_prefs")

@Module
@InstallIn(SingletonComponent::class)
object SessionListPreferencesModule {

    @Provides
    @Singleton
    @SessionListDataStore
    fun provideSessionListDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.sessionListDataStore
}
