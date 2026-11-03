package com.anderson.onedrivesync.auth

data class DeviceCodeInfo(
    val userCode: String,
    val verificationUri: String,
    val expiresAtMs: Long,
    val message: String? = null,
)

sealed interface AuthState {
    data object SignedOut : AuthState
    data class Pending(val deviceCode: DeviceCodeInfo) : AuthState
    data object SignedIn : AuthState
    data class Error(val message: String) : AuthState
}

open class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AuthRequiredException(message: String = "Authentication required") : AuthException(message)

class AuthCancelledException : AuthException("Sign-in cancelled")
