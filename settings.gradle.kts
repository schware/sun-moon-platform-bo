rootProject.name = "sun-moon-platform-bo"

// The kernel is a git submodule under core/, built from source alongside BO
// rather than resolved from an artifact registry. There is no registry, and
// standing one up would mean a publish token to keep alive for a two-repo
// split — a composite build costs nothing and keeps "edit the kernel, see it
// break here" a single Gradle invocation (docs/adr/0014).
includeBuild("core")
