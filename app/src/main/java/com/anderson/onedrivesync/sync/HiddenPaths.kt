package com.anderson.onedrivesync.sync

/**
 * Dot entries are outside the sync, both ways: `.nomedia`, `.thumbnails`, `.git/config` are neither
 * uploaded nor downloaded, and an entry already in the index for one of them is ignored instead of
 * becoming a deletion request. Nothing is deleted on either side because of this rule.
 *
 * The rule is one: a name is hidden when it starts with `.`, a relative path is hidden when any of
 * its segments is. It is applied at every entry point (scan, delta tree, engine, planner, local fs)
 * so no single filter has to be trusted.
 */
fun isHiddenName(name: String): Boolean = name.startsWith(".")

/** The empty path has no segment, so it is visible. Only `/` separates segments. */
fun isHiddenRelPath(relPath: String): Boolean = relPath.split('/').any(::isHiddenName)
