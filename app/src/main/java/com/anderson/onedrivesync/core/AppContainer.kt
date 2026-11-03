package com.anderson.onedrivesync.core

import android.content.Context
import com.anderson.onedrivesync.auth.AuthManager
import com.anderson.onedrivesync.auth.TokenStore
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.DbIndexStore
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.graph.GraphClient
import com.anderson.onedrivesync.graph.GraphRemoteApi
import com.anderson.onedrivesync.sync.FileLocalFs
import com.anderson.onedrivesync.sync.IndexStore
import com.anderson.onedrivesync.sync.LocalFs
import com.anderson.onedrivesync.sync.RemoteApi
import com.anderson.onedrivesync.sync.SyncEngine
import com.anderson.onedrivesync.update.UpdateApplier
import com.anderson.onedrivesync.update.UpdateChecker
import java.io.File
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

    val indexStore: IndexStore by lazy { DbIndexStore(db) }

    val remoteApi: RemoteApi by lazy {
        // The HTTP status and Graph error code are the only diagnostic signal for a failed call.
        GraphRemoteApi(graphClient) { message ->
            indexStore.log(System.currentTimeMillis(), "WARN", "GraphRemoteApi", message)
        }
    }

    val localFsFor: (Pair) -> LocalFs by lazy {
        { pair ->
            FileLocalFs(File(pair.localPath)) { message ->
                indexStore.log(System.currentTimeMillis(), "WARN", "LocalFs", message)
            }
        }
    }

    val syncEngine: SyncEngine by lazy { SyncEngine(indexStore, remoteApi, localFsFor) }

    val updateChecker: UpdateChecker by lazy { UpdateChecker(okHttpClient) }

    val updateApplier: UpdateApplier by lazy { UpdateApplier(okHttpClient) }
}
