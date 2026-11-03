package com.anderson.onedrivesync.auth

internal class FakeTokenStore(var tokens: Tokens? = null) : TokenStorage {
    override fun load(): Tokens? = tokens

    override fun save(tokens: Tokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}
