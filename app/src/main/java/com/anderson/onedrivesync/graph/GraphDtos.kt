package com.anderson.onedrivesync.graph

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val graphJson = Json { ignoreUnknownKeys = true }

@Serializable
data class DriveItem(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val parentReference: ParentReference? = null,
    val folder: FolderFacet? = null,
    val file: FileFacet? = null,
    val fileSystemInfo: FileSystemInfo? = null,
    val deleted: DeletedFacet? = null,
    @SerialName("@removed") val removed: RemovedFacet? = null,
)

@Serializable
data class ParentReference(
    val id: String? = null,
    val path: String? = null,
)

@Serializable
data class FolderFacet(
    val childCount: Long? = null,
)

@Serializable
data class FileFacet(
    val mimeType: String? = null,
    val hashes: FileHashes? = null,
)

@Serializable
data class FileHashes(
    @SerialName("quickXorHash") val quickXorHash: String? = null,
)

@Serializable
data class FileSystemInfo(
    val lastModifiedDateTime: String? = null,
    val createdDateTime: String? = null,
) {
    val lastModifiedEpochMs: Long? get() = epochMs(lastModifiedDateTime)
    val createdEpochMs: Long? get() = epochMs(createdDateTime)
}

@Serializable
data class DeletedFacet(
    val state: String? = null,
)

@Serializable
data class RemovedFacet(
    val reason: String? = null,
)

@Serializable
data class DeltaPage(
    val value: List<DriveItem> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null,
)

@Serializable
data class DriveUser(
    val displayName: String? = null,
    val userPrincipalName: String? = null,
)

@Serializable
internal data class UploadSession(
    @SerialName("uploadUrl") val uploadUrl: String,
)

internal fun epochMs(iso8601: String?): Long? =
    iso8601?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
