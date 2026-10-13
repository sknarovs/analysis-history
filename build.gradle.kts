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
    // RealDataTest reads the real reports from results/ unless -Danalysis.results=<folder> points elsewhere.
    providers.systemProperty("analysis.results").orNull?.let { systemProperty("analysis.results", it) }
    // PDFBox writes its system-font cache here instead of the home folder, but only if the folder exists. With the
    // bundled font mapper installed first, PDFBox never scans system fonts, so a cache file here fails the build.
    val fontCache = layout.buildDirectory.dir("pdfbox-fontcache").get().asFile
    systemProperty("pdfbox.fontcache", fontCache.path)
    doFirst {
        fontCache.mkdirs()
        fontCache.resolve(".pdfbox.cache").delete()
    }
    doLast {
        val cache = fontCache.resolve(".pdfbox.cache")
        if (cache.exists()) {
            throw GradleException(
                "A test made PDFBox scan the system fonts and write $cache. Create PDFBox fonts only after " +
                    "PdfText.useBundledFonts() (TestPdf.create() calls it)."
            )
        }
    }
}

graalvmNative {
    toolchainDetection = false
    metadataRepository { enabled = true }
    binaries {
        all {
            resources.autodetect()
            buildArgs.addAll("-Djava.awt.headless=true", "-H:+AddAllCharsets", "-march=compatibility")
        }
        named("main") {
            imageName = "analysis-history"
            if (providers.gradleProperty("static").isPresent) buildArgs.addAll("--static", "--libc=musl")
        }
    }
}

tasks.register<JavaExec>("generateSamples") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "lv.sknarovs.analysishistory.SampleReports"
    args(layout.buildDirectory.dir("samples").get().asFile.path)
}
