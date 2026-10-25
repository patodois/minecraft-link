plugins {
    id("java-library")
    id("com.gtnewhorizons.retrofuturagradle") version "2.0.6"
}

group = "dev.patodois"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        // Azul covers the most platforms for Java 8 toolchains, crucially including macOS arm64.
        vendor.set(JvmVendorSpec.AZUL)
    }
}

minecraft {
    mcVersion.set("1.12.2")
    username.set("Developer")
}

repositories {
    mavenCentral()
}

// Minecraft 1.12.2 starts its narrator (text2speech -> java-objc-bridge 1.0.0) while it initialises, and that library
// carries a native for Intel Macs only ("have 'i386,x86_64', need 'arm64'"): the client crashes before the title
// screen on Apple silicon. For development runs the narrator library is replaced by a no-op stand-in (dev/narrator-shim,
// not part of the mod jar). The newer java-objc-bridge versions are Java 11 bytecode and cannot load on Java 8.
val narratorShim = tasks.register<JavaCompile>("narratorShim") {
    source(fileTree("dev/narrator-shim"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("narrator-shim"))
    options.release.set(8)
}

// The passthrough link protocol (pure Java 8), built from ../link-java and merged into the mod jar.
// A plain jar file dependency (built by the included build first), not a project dependency: RetroFuturaGradle asks
// every dependency for its "deobfuscated" variant, which a project of another build cannot offer.
val linkJavaJar = file("../link-java/build/libs/link-java-0.1.1.jar")
val buildLinkJava = gradle.includedBuild("link-java").task(":jar")

dependencies {
    implementation(files(linkJavaJar).builtBy(buildLinkJava))

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.joml:joml:1.10.8") // test-only: the tests build reference matrices with it
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "768m"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("mcmod.info") {
        expand(mapOf("modVersion" to v))
    }
}

tasks.jar {
    archiveBaseName.set("minecraft-link-forge-1.12.2") // minecraft-link-<loader>-<minecraft version>-<mod version>.jar
    dependsOn(buildLinkJava)
    from({ zipTree(linkJavaJar) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "module-info.class")
    }
    from("LICENSE-MIT") { rename { "${it}_minecraft-link" } }
    from("LICENSE-APACHE") { rename { "${it}_minecraft-link" } }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------------------------------------------------------------------------------------------------------------
// Development runs (./gradlew runClient). Mac with 16 GB shared with other builds: the game gets 1500 MB.
// Every launch first writes the sound off into run/options.txt (and a few settings a scripted run needs), keeping
// the other lines of the file as they are.

val runOptions = linkedMapOf(
    "soundCategory_master" to "0.0",   // Minecraft 1.12.2: master volume (the 1.7.10 equivalent is soundVolume + musicVolume)
    "pauseOnLostFocus" to "false",      // a scripted run may not have the window focus
    "fullscreen" to "false",
    "maxFps" to "60",
    "tutorialStep" to "none",           // no tutorial toast over the screenshots
)

fun writeRunOptions(dir: File) {
    dir.mkdirs()
    val f = File(dir, "options.txt")
    val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
    for ((k, v) in runOptions) {
        val i = lines.indexOfFirst { it.startsWith("$k:") }
        if (i >= 0) lines[i] = "$k:$v" else lines.add("$k:$v")
    }
    f.writeText(lines.joinToString("\n") + "\n")
}

fun JavaExec.withNarratorShim() {
    dependsOn(narratorShim)
    classpath = files(narratorShim.map { it.destinationDirectory }) + classpath.filter { !it.name.startsWith("text2speech") }
}

// runClient: the deobfuscated dev client. runObfClient: the Forge client with the reobfuscated jar of this mod, the way a
// player's game runs it (SRG names), so it proves the release jar loads.
for (name in listOf("runClient", "runObfClient")) {
    tasks.named<JavaExec>(name) {
        withNarratorShim()
        minHeapSize = "512m"
        maxHeapSize = "1500m"
        doFirst { writeRunOptions(workingDir) }
        // A scripted run (see AutoTest): ./gradlew runClient -Pautotest=/path/script.txt -Pshots=/path/dir
        if (project.hasProperty("autotest")) {
            systemProperty("minecraftlink.autotest", project.property("autotest").toString())
            systemProperty("minecraftlink.shots", (project.findProperty("shots") ?: "run/shots").toString())
            // a fresh world for every scripted run
            doFirst { File(workingDir, "saves/linktest").deleteRecursively() }
        }
    }
}

tasks.named<JavaExec>("runVanillaClient") {
    withNarratorShim()
    minHeapSize = "512m"
    maxHeapSize = "1500m"
    doFirst { writeRunOptions(workingDir) }
}
