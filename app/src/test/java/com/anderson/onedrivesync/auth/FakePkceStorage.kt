package com.anderson.onedrivesync.auth

internal class FakePkceStorage(var pending: PendingPkce? = null) : PkceStorage {
    override fun savePkce(pkce: PendingPkce) {
        pending = pkce
    }

    override fun loadPkce(): PendingPkce? = pending

    override fun clearPkce() {
        pending = null
    }
}
