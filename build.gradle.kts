plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.blacklauncher"
version = "1.0.0"
base.archivesName.set("black-launcher")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/**
 * Optional proprietary LAN / online sources (not published to the public GitHub tree).
 * Present locally as {@code src/private/java}; open-source clones omit this directory.
 */
val privateJava = file("src/private/java")
val privateTestJava = file("src/private/test/java")
sourceSets {
    main {
        if (privateJava.isDirectory) {
            java.srcDir(privateJava)
        }
    }
    test {
        if (privateTestJava.isDirectory) {
            java.srcDir(privateTestJava)
        }
    }
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}

javafx {
    version = "21.0.2"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.web")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("com.blacklauncher.Main")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.blacklauncher.Main"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

/** Stage files for Inno Setup under build/dist/payload (§8). */
tasks.register<Copy>("stageInstallerPayload") {
    group = "distribution"
    description = "Assemble BlackLauncher payload (jar + bat) for the Windows installer"
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("dist/payload"))
    from(tasks.jar) {
        rename { "black-launcher-${project.version}.jar" }
    }
    from("installer/payload") {
        include("BlackLauncher.bat")
    }
    doLast {
        val payload = layout.buildDirectory.get().asFile.resolve("dist/payload")
        println("Installer payload staged at: ${payload.absolutePath}")
    }
}

/** Build BlackLauncher-Setup-*.exe via Inno Setup ISCC (must be installed). */
tasks.register<Exec>("buildInstaller") {
    group = "distribution"
    description = "Compile Windows setup.exe with Inno Setup (requires ISCC on PATH or ISCC env)"
    dependsOn("stageInstallerPayload")
    workingDir = file("installer")
    val isccEnv = System.getenv("ISCC")
    val candidates = listOfNotNull(
        isccEnv,
        "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe",
        "C:\\Program Files\\Inno Setup 6\\ISCC.exe",
        "ISCC.exe"
    )
    val iscc = candidates.firstOrNull { p ->
        p == "ISCC.exe" || file(p).exists()
    } ?: "ISCC.exe"
    commandLine(iscc, "BlackLauncher.iss")
    doFirst {
        val payload = layout.buildDirectory.get().asFile.resolve("dist/payload")
        require(payload.resolve("BlackLauncher.bat").exists()) {
            "Missing payload. Run stageInstallerPayload first."
        }
        println("Using ISCC: $iscc")
    }
}
