plugins { id("gg.grounds.kotlin-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    implementation("tools.jackson.core:jackson-databind:3.1.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.0")
    implementation("io.nats:jnats:2.26.0")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
