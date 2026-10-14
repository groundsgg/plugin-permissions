plugins { id("gg.grounds.velocity-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    implementation(project(":common"))
    implementation("gg.grounds:library-i18n:0.3.0")
    // Never shaded: the registry has to be the class plugin-proxy loaded, or the query would be
    // published into a map nobody reads. 0.7.0 is the first version carrying PlayerRoleQuery.
    compileOnly("gg.grounds:plugin-proxy-api:0.7.0")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito.kotlin:mockito-kotlin")
    testImplementation("org.slf4j:slf4j-api")
    testImplementation("com.velocitypowered:velocity-api")
    testImplementation("gg.grounds:plugin-proxy-api:0.7.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
