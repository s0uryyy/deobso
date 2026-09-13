plugins { java }
repositories { mavenCentral() }
java { toolchain.languageVersion = JavaLanguageVersion.of(17) }
sourceSets.main { java.setSrcDirs(listOf("../src/_legacy/_shared")) }
dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.google.errorprone:error_prone_annotations:2.38.0")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
tasks.test { useJUnitPlatform() }
