// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.diffplug.spotless") version "8.10.4"
}

val ktfmtVersion = providers.gradleProperty("ktfmtVersion").get()

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}

val ktfmtCli = configurations.create("ktfmtCli")

dependencies {
    add(ktfmtCli.name, "com.facebook:ktfmt:$ktfmtVersion:with-dependencies")
}

tasks.register<Sync>("prepareKtfmtCli") {
    from(ktfmtCli)
    into(layout.buildDirectory.dir("formatter/$ktfmtVersion"))
}
