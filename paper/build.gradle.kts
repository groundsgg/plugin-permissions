plugins { id("gg.grounds.paper-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))
    implementation(project(":common"))
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.114.0")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
