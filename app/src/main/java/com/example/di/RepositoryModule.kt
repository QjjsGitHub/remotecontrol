package com.example.di

import com.example.data.repository.RemoteControlRepository
import com.example.data.repository.RemoteControlRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideRemoteControlRepository(): RemoteControlRepository {
        return RemoteControlRepositoryImpl()
    }
}
