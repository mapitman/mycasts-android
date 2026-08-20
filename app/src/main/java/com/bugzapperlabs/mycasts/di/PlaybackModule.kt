package com.bugzapperlabs.mycasts.di

import com.bugzapperlabs.mycasts.playback.AndroidNetworkTypeChecker
import com.bugzapperlabs.mycasts.playback.NetworkTypeChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds
    abstract fun bindNetworkTypeChecker(impl: AndroidNetworkTypeChecker): NetworkTypeChecker
}
