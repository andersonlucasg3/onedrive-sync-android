package com.anderson.onedrivesync.core

import android.content.Context
import com.anderson.onedrivesync.auth.AuthManager
import com.anderson.onedrivesync.auth.TokenStore
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.graph.GraphClient
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val prefs: Prefs by lazy { Prefs(appContext) }

    val db: Db by lazy { Db(appContext) }

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val authManager: AuthManager by lazy {
        AuthManager(clientId = { prefs.clientId }, tokens = tokenStore, http = okHttpClient)
    }

    val graphClient: GraphClient by lazy {
        GraphClient(tokenProvider = { forceRefresh -> authManager.accessToken(forceRefresh) }, http = okHttpClient)
    }
}
