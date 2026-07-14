// Root build file.
//
// This project remains a single Gradle module (`app`) during the Phase 0
// feasibility spike (see docs/implementation-plan.md section 5 and 28).
// It will be split into `terminal-api`, `terminal-ghostty`, and
// `native-host` modules once the terminal prototype works.

plugins {
    // Applied only in subprojects; declared here (with apply false) so the
    // version is resolved once for the whole build.
    id("org.openjfx.javafxplugin") version "0.1.0" apply false
}

tasks.register<Exec>("verifyEnvironment") {
    group = "verification"
    description = "Runs scripts/verify-environment.sh to check local dev prerequisites."
    commandLine("bash", "${rootDir}/scripts/verify-environment.sh")
}
