plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinAllOpen)
    alias(libs.plugins.quarkus)
}

allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.persistence.Entity")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-kotlin-serialization")
    implementation("io.quarkus:quarkus-rest-client")
    implementation("io.quarkus:quarkus-rest-client-kotlin-serialization")
    implementation("io.quarkus:quarkus-oidc")
    implementation("io.quarkus:quarkus-scheduler")

    implementation(libs.quarkus.mcp.server.sse)
    implementation(libs.quarkus.jdbc.sqlite)
    implementation(libs.quarkus.oidc.proxy)

    implementation(projects.shared)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation("io.quarkus:quarkus-junit5")
}
