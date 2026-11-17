import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The engine is plain Kotlin (no Android imports) so its rules, levels and balance simulations
// run as fast JVM tests. It compiles with the JDK 21 toolchain and emits Java 17 bytecode for the app.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "3g"
    // Balance tests read these: -Pseeds=200 for the full report-grade run.
    systemProperty("balance.seeds", (project.findProperty("seeds") ?: "40").toString())
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ./gradlew :engine:balanceReport [-Pseeds=200] writes docs/BALANCE_REPORT.md
tasks.register<JavaExec>("balanceReport") {
    group = "verification"
    description = "Runs the headless balance simulation and writes docs/BALANCE_REPORT.md"
    mainClass.set("com.ngstudio.paperclash2.engine.sim.BalanceReportKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "3g"
    args(
        rootProject.layout.projectDirectory.file("docs/BALANCE_REPORT.md").asFile.absolutePath,
        (project.findProperty("seeds") ?: "200").toString(),
        (project.findProperty("sampleSeeds") ?: "12").toString(),
    )
}

// ./gradlew :engine:balanceDiag -Plevels=20,100,300 [-Pseeds=16]: per-level win / pop / end-% details
tasks.register<JavaExec>("balanceDiag") {
    group = "verification"
    mainClass.set("com.ngstudio.paperclash2.engine.sim.BalanceReportKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "3g"
    val lv = (project.findProperty("levels") ?: "20,100").toString().split(",")
    args(listOf("diag", (project.findProperty("seeds") ?: "16").toString()) + lv)
}
