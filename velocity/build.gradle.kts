plugins { id("gg.grounds.velocity-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    implementation(project(":common"))
    implementation("gg.grounds:library-i18n:0.4.4")
    // Never shaded: the registry has to be the class plugin-proxy loaded, or the query would be
    // published into a map nobody reads. 0.7.0 is the first version carrying PlayerRoleQuery.
    compileOnly("gg.grounds:plugin-proxy-api:2.6.0")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito.kotlin:mockito-kotlin")
    testImplementation("org.slf4j:slf4j-api")
    testImplementation("com.velocitypowered:velocity-api")
    testImplementation("gg.grounds:plugin-proxy-api:2.6.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Keep the publishing artifact version with its catalog when bundled into a host JAR.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    inputs.property("permissionManifestVersion", project.version.toString())
    filesMatching("META-INF/grounds/permissions/*.json") {
        expand("version" to project.version.toString())
    }
}
