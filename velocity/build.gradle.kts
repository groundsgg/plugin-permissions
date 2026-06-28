plugins { id("gg.grounds.velocity-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.79.0")
    implementation("io.grpc:grpc-stub")
    implementation("tools.jackson.core:jackson-databind:3.1.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.slf4j:slf4j-api")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
