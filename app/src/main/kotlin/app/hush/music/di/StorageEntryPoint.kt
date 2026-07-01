package app.hush.music.di

import app.hush.music.storage.ImportLegacyStorageUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StorageEntryPoint {
    fun importLegacyStorage(): ImportLegacyStorageUseCase
}
