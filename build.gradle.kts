// Shared versioning for both artifacts: the Paper plugin and the NeoForge mod ship under the same
// <versionLine>.<patch> number. The patch is derived from the git commit count so that a merge to
// main is all it takes to advance the version; see gradle.properties for the calibration constant.

val versionLine = providers.gradleProperty("versionLine").get()
val versionCommitOffset = providers.gradleProperty("versionCommitOffset").get().toInt()
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
val gitDirty = providers.exec {
    commandLine("git", "status", "--porcelain")
}.standardOutput.asText.get().isNotBlank()
val automaticPatch = (gitCommitCount - versionCommitOffset).coerceAtLeast(0)
version = "$versionLine.$automaticPatch${if (gitDirty) "-dev" else ""}"

allprojects {
    group = "vip.naya.finiteloot"
    version = rootProject.version
}
