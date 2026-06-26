/*
 * Hush — personal fork of ArchiveTune
 */

package moe.rukamori.archivetune

object HushLinks {
    const val GITHUB_OWNER = "maxinjohn"
    const val GITHUB_REPO = "Hush"
    const val GITHUB_REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val GITHUB_RELEASES_URL = "$GITHUB_REPO_URL/releases"
    const val GITHUB_API_RELEASES_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"
    const val GITHUB_RAW_DEV_BASE = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/dev"

    const val TOGETHER_ENDPOINT_SOURCE_URL = "$GITHUB_RAW_DEV_BASE/ArchiveTuneKoiverseServer.txt"

    const val UPSTREAM_GITHUB_OWNER = "ArchiveTuneApp"
    const val UPSTREAM_GITHUB_REPO = "ArchiveTune"
    const val UPSTREAM_GITHUB_REPO_URL = "https://github.com/$UPSTREAM_GITHUB_OWNER/$UPSTREAM_GITHUB_REPO"
    const val UPSTREAM_CONTRIBUTORS_URL =
        "https://github.com/$UPSTREAM_GITHUB_OWNER/$UPSTREAM_GITHUB_REPO/graphs/contributors"

    const val APK_ARTIFACT_BASE_NAME = "hush"

    const val PRIVACY_POLICY_URL = "$GITHUB_REPO_URL/blob/dev/PRIVACY.md"
    const val CONTRIBUTORS_URL = UPSTREAM_CONTRIBUTORS_URL
}
