package com.anderson.onedrivesync.graph

class GraphException(
    val status: Int,
    val code: String?,
    message: String,
    val location: String? = null,
) : Exception(message)

fun GraphException.isNotFound(): Boolean = status == 404

fun GraphException.isGone(): Boolean = status == 410
