plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("io.github.commandertvis.huemanager.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)

    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverSessions)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverSse)
    implementation(libs.ktor.serializationJson)

    implementation(libs.mcp.sdk)
    implementation(libs.mcp.sdk.server)

    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.dotenv)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.mcp.sdk)
    testImplementation(kotlin("test-junit"))
}
