plugins { id("gg.grounds.paper-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))
    implementation(project(":common"))
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
