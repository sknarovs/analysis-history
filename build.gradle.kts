plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.dhatim:fastexcel:0.20.2")
    compileOnly("org.graalvm.sdk:nativeimage:25.4.4.1.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass = "lv.sknarovs.analysishistory.Main" }

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
