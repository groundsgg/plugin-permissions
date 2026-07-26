plugins { id("gg.grounds.kotlin-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    implementation("tools.jackson.core:jackson-databind:3.1.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
