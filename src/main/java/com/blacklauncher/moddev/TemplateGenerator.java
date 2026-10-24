package com.blacklauncher.moddev;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.Validation;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Programmatically writes a Fabric Loom mod project (fabric-example-mod style).
 */
public class TemplateGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateGenerator.class);

    private static final String GRADLE_VERSION = "8.12";
    private static final String KOTLIN_ADAPTER = "1.13.2+kotlin.2.1.20";
    private static final String WRAPPER_JAR_URL =
            "https://raw.githubusercontent.com/FabricMC/fabric-example-mod/1.21/gradle/wrapper/gradle-wrapper.jar";

    private final FabricMetaClient meta;

    public TemplateGenerator(AppConfig config) {
        this.meta = new FabricMetaClient(config);
    }

    public TemplateGenerator(FabricMetaClient meta) {
        this.meta = meta != null ? meta : new FabricMetaClient((AppConfig) null);
    }

    public Path generate(TemplateRequest req, Path outputDir) throws Exception {
        String err = TemplateValidation.validate(req);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        String modId = TemplateValidation.normalizeModId(req.modId());
        if (!TemplateValidation.isValidModId(modId)) {
            throw new IllegalArgumentException(TemplateValidation.modIdViolation(modId));
        }
        String packageName = req.packageName().trim();
        String modName = req.modName().trim();
        String mcVersion = req.minecraftVersion().trim();
        String className = toClassName(modName, modId);
        String mavenGroup = packageName.contains(".")
                ? packageName.substring(0, packageName.lastIndexOf('.'))
                : packageName;

        String yarn = Validation.isNonBlank(req.yarnVersion())
                ? req.yarnVersion().trim()
                : meta.latestStableYarn(mcVersion).orElseThrow(
                () -> new IllegalStateException("无法解析 Yarn 映射: " + mcVersion));
        String loader = Validation.isNonBlank(req.loaderVersion())
                ? req.loaderVersion().trim()
                : meta.latestStableLoader(mcVersion).orElseThrow(
                () -> new IllegalStateException("无法解析 Fabric Loader: " + mcVersion));
        String fabricApi = Validation.isNonBlank(req.fabricApiVersion())
                ? req.fabricApiVersion().trim()
                : meta.resolveFabricApi(mcVersion).orElse("0.0.0+" + mcVersion);

        Path projectDir = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(projectDir);

        boolean wrapperOk = downloadWrapperJar(projectDir);
        writeGradleWrapperProps(projectDir);
        writeGradlewScripts(projectDir);

        writeText(projectDir.resolve("gradle.properties"), gradleProperties(
                mcVersion, yarn, loader, fabricApi, mavenGroup, modId));

        if (req.kotlinDsl()) {
            writeText(projectDir.resolve("settings.gradle.kts"), settingsKts(modId));
            writeText(projectDir.resolve("build.gradle.kts"), buildKts(req, modId));
        } else {
            writeText(projectDir.resolve("settings.gradle"), settingsGroovy(modId));
            writeText(projectDir.resolve("build.gradle"), buildGroovy(req, modId));
        }

        writeText(projectDir.resolve(".gitignore"), gitignore());
        writeText(projectDir.resolve("README.md"), readme(modName, modId, wrapperOk));

        Path resources = projectDir.resolve("src/main/resources");
        Files.createDirectories(resources);
        writeText(resources.resolve("fabric.mod.json"), fabricModJson(req, modId, modName, packageName, className));
        writeText(resources.resolve(modId + ".mixins.json"), mixinsJson(packageName));
        Path lang = resources.resolve("assets").resolve(modId).resolve("lang");
        Files.createDirectories(lang);
        writeText(lang.resolve("en_us.json"), "{\n  \"" + modId + ".title\": \"" + escapeJson(modName) + "\"\n}\n");

        Path mainSrc = projectDir.resolve("src/main").resolve(req.kotlin() ? "kotlin" : "java")
                .resolve(packageToPath(packageName));
        Files.createDirectories(mainSrc);
        if (req.kotlin()) {
            writeText(mainSrc.resolve(className + ".kt"), kotlinMain(packageName, className, modId));
        } else {
            writeText(mainSrc.resolve(className + ".java"), javaMain(packageName, className, modId));
        }

        if (req.splitSources()) {
            Path clientPkg = projectDir.resolve("src/client").resolve(req.kotlin() ? "kotlin" : "java")
                    .resolve(packageToPath(packageName));
            Files.createDirectories(clientPkg);
            String clientClass = className + "Client";
            if (req.kotlin()) {
                writeText(clientPkg.resolve(clientClass + ".kt"), kotlinClient(packageName, clientClass, modId));
            } else {
                writeText(clientPkg.resolve(clientClass + ".java"), javaClient(packageName, clientClass, modId));
            }
        }

        if (req.dataGen()) {
            Path dataPkg = projectDir.resolve("src/main").resolve(req.kotlin() ? "kotlin" : "java")
                    .resolve(packageToPath(packageName));
            Files.createDirectories(dataPkg);
            String dataClass = className + "DataGenerator";
            if (req.kotlin()) {
                writeText(dataPkg.resolve(dataClass + ".kt"), kotlinDataGen(packageName, dataClass));
            } else {
                writeText(dataPkg.resolve(dataClass + ".java"), javaDataGen(packageName, dataClass));
            }
        }

        return projectDir;
    }

    public Path generateToZip(TemplateRequest req, Path zipPath) throws Exception {
        Path parent = zipPath.getParent() != null ? zipPath.getParent() : Path.of(".");
        String base = zipPath.getFileName().toString();
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            base = base.substring(0, base.length() - 4);
        }
        Path tempProject = parent.resolve(base + "-src-" + System.currentTimeMillis());
        try {
            generate(req, tempProject);
            TemplatePackager.zip(tempProject, zipPath);
            return zipPath;
        } finally {
            deleteRecursive(tempProject);
        }
    }

    static String toClassName(String modName, String modId) {
        String fromName = toPascalCase(modName);
        if (Validation.isNonBlank(fromName) && Character.isLetter(fromName.charAt(0))) {
            return fromName.endsWith("Mod") ? fromName : fromName + "Mod";
        }
        String fromId = toPascalCase(modId.replace('-', '_'));
        if (!Validation.isNonBlank(fromId)) {
            return "ExampleMod";
        }
        return fromId.endsWith("Mod") ? fromId : fromId + "Mod";
    }

    private static String toPascalCase(String raw) {
        if (!Validation.isNonBlank(raw)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
            } else {
                cap = true;
            }
        }
        return sb.toString();
    }

    private static String packageToPath(String packageName) {
        return packageName.replace('.', '/');
    }

    private boolean downloadWrapperJar(Path projectDir) {
        Path jar = projectDir.resolve("gradle/wrapper/gradle-wrapper.jar");
        try {
            Files.createDirectories(jar.getParent());
            OkHttpClient client = HttpClientFactory.create(null);
            Request request = new Request.Builder()
                    .url(WRAPPER_JAR_URL)
                    .header("User-Agent", "BlackLauncher")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("empty body");
                }
                Files.write(jar, body.bytes());
                return true;
            }
        } catch (Exception e) {
            LOG.warn("Failed to download gradle-wrapper.jar; project still generated", e);
            return false;
        }
    }

    private void writeGradleWrapperProps(Path projectDir) throws IOException {
        String props = """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=https\\://services.gradle.org/distributions/gradle-%s-bin.zip
                networkTimeout=10000
                validateDistributionUrl=true
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.formatted(GRADLE_VERSION);
        writeText(projectDir.resolve("gradle/wrapper/gradle-wrapper.properties"), props);
    }

    private void writeGradlewScripts(Path projectDir) throws IOException {
        writeText(projectDir.resolve("gradlew.bat"), """
                @rem Simplified Gradle startup for Windows
                @echo off
                set DIRNAME=%~dp0
                if "%DIRNAME%" == "" set DIRNAME=.
                set APP_HOME=%DIRNAME%
                set CLASSPATH=%APP_HOME%\\gradle\\wrapper\\gradle-wrapper.jar
                set JAVA_EXE=java.exe
                if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\\bin\\java.exe
                "%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
                """);
        writeText(projectDir.resolve("gradlew"), """
                #!/bin/sh
                DIR="$(cd "$(dirname "$0")" && pwd)"
                CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
                exec "${JAVA_HOME:-}/bin/java" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
                """);
    }

    private static String gradleProperties(String mc, String yarn, String loader, String api,
                                           String group, String modId) {
        return """
                org.gradle.jvmargs=-Xmx1G
                org.gradle.parallel=true
                
                minecraft_version=%s
                yarn_mappings=%s
                loader_version=%s
                loom_version=%s
                fabric_api_version=%s
                
                mod_version=1.0.0
                maven_group=%s
                archives_base_name=%s
                """.formatted(mc, yarn, loader, FabricMetaClient.LOOM_VERSION, api, group, modId);
    }

    private static String settingsGroovy(String modId) {
        return """
                pluginManagement {
                \trepositories {
                \t\tmaven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
                \t\tmavenCentral()
                \t\tgradlePluginPortal()
                \t}
                }
                
                rootProject.name = '%s'
                """.formatted(modId);
    }

    private static String settingsKts(String modId) {
        return """
                pluginManagement {
                \trepositories {
                \t\tmaven("https://maven.fabricmc.net/") { name = "Fabric" }
                \t\tmavenCentral()
                \t\tgradlePluginPortal()
                \t}
                }
                
                rootProject.name = "%s"
                """.formatted(modId);
    }

    private static String buildGroovy(TemplateRequest req, String modId) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        sb.append("\tid 'fabric-loom' version \"${project.loom_version}\"\n");
        sb.append("\tid 'maven-publish'\n");
        if (req.kotlin()) {
            sb.append("\tid 'org.jetbrains.kotlin.jvm' version '2.1.20'\n");
        }
        sb.append("}\n\n");
        sb.append("version = project.mod_version\n");
        sb.append("group = project.maven_group\n\n");
        sb.append("base {\n\tarchivesName = project.archives_base_name\n}\n\n");
        sb.append("repositories {\n\tmavenCentral()\n}\n\n");
        sb.append("loom {\n");
        if (req.splitSources()) {
            sb.append("\tsplitEnvironmentSourceSets()\n\n");
            sb.append("\tmods {\n\t\t\"").append(modId).append("\" {\n");
            sb.append("\t\t\tsourceSet sourceSets.main\n");
            sb.append("\t\t\tsourceSet sourceSets.client\n");
            sb.append("\t\t}\n\t}\n");
        }
        if (req.dataGen()) {
            sb.append("\truns {\n\t\tdatagen {\n");
            sb.append("\t\t\tinherit client\n");
            sb.append("\t\t\tname \"Data Generation\"\n");
            sb.append("\t\t\tvmArg \"-Dfabric-api.datagen\"\n");
            sb.append("\t\t\tvmArg \"-Dfabric-api.datagen.output-dir=${file(\"src/main/generated\")}\"\n");
            sb.append("\t\t\tvmArg \"-Dfabric-api.datagen.modid=").append(modId).append("\"\n");
            sb.append("\t\t\trunDir \"build/datagen\"\n");
            sb.append("\t\t}\n\t}\n");
        }
        sb.append("}\n\n");
        sb.append("dependencies {\n");
        sb.append("\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n");
        sb.append("\tmappings \"net.fabricmc:yarn:${project.yarn_mappings}:v2\"\n");
        sb.append("\tmodImplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n");
        sb.append("\tmodImplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n");
        if (req.kotlin()) {
            sb.append("\tmodImplementation \"net.fabricmc:fabric-language-kotlin:")
                    .append(KOTLIN_ADAPTER).append("\"\n");
        }
        sb.append("}\n\n");
        sb.append("processResources {\n");
        sb.append("\tinputs.property \"version\", project.version\n");
        sb.append("\tfilesMatching(\"fabric.mod.json\") {\n");
        sb.append("\t\texpand \"version\": project.version\n");
        sb.append("\t}\n}\n\n");
        sb.append("tasks.withType(JavaCompile).configureEach {\n");
        sb.append("\tit.options.release = 21\n}\n\n");
        sb.append("java {\n\twithSourcesJar()\n\tsourceCompatibility = JavaVersion.VERSION_21\n");
        sb.append("\ttargetCompatibility = JavaVersion.VERSION_21\n}\n\n");
        sb.append("jar {\n\tfrom(\"LICENSE\") {\n\t\trename { \"${it}_${project.base.archivesName.get()}\" }\n\t}\n}\n");
        return sb.toString();
    }

    private static String buildKts(TemplateRequest req, String modId) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        sb.append("\tid(\"fabric-loom\") version \"").append(FabricMetaClient.LOOM_VERSION).append("\"\n");
        sb.append("\t`maven-publish`\n");
        if (req.kotlin()) {
            sb.append("\tkotlin(\"jvm\") version \"2.1.20\"\n");
        }
        sb.append("}\n\n");
        sb.append("version = project.property(\"mod_version\") as String\n");
        sb.append("group = project.property(\"maven_group\") as String\n\n");
        sb.append("base {\n\tarchivesName.set(project.property(\"archives_base_name\") as String)\n}\n\n");
        sb.append("repositories {\n\tmavenCentral()\n}\n\n");
        sb.append("loom {\n");
        if (req.splitSources()) {
            sb.append("\tsplitEnvironmentSourceSets()\n\n");
            sb.append("\tmods {\n\t\tcreate(\"").append(modId).append("\") {\n");
            sb.append("\t\t\tsourceSet(sourceSets.main.get())\n");
            sb.append("\t\t\tsourceSet(sourceSets.getByName(\"client\"))\n");
            sb.append("\t\t}\n\t}\n");
        }
        if (req.dataGen()) {
            sb.append("\truns {\n\t\tcreate(\"datagen\") {\n");
            sb.append("\t\t\tclient()\n");
            sb.append("\t\t\tname(\"Data Generation\")\n");
            sb.append("\t\t\tvmArg(\"-Dfabric-api.datagen\")\n");
            sb.append("\t\t\tvmArg(\"-Dfabric-api.datagen.output-dir=${file(\"src/main/generated\")}\")\n");
            sb.append("\t\t\tvmArg(\"-Dfabric-api.datagen.modid=").append(modId).append("\")\n");
            sb.append("\t\t\trunDir(\"build/datagen\")\n");
            sb.append("\t\t}\n\t}\n");
        }
        sb.append("}\n\n");
        sb.append("dependencies {\n");
        sb.append("\tminecraft(\"com.mojang:minecraft:${property(\"minecraft_version\")}\")\n");
        sb.append("\tmappings(\"net.fabricmc:yarn:${property(\"yarn_mappings\")}:v2\")\n");
        sb.append("\tmodImplementation(\"net.fabricmc:fabric-loader:${property(\"loader_version\")}\")\n");
        sb.append("\tmodImplementation(\"net.fabricmc.fabric-api:fabric-api:${property(\"fabric_api_version\")}\")\n");
        if (req.kotlin()) {
            sb.append("\tmodImplementation(\"net.fabricmc:fabric-language-kotlin:")
                    .append(KOTLIN_ADAPTER).append("\")\n");
        }
        sb.append("}\n\n");
        sb.append("tasks.processResources {\n");
        sb.append("\tinputs.property(\"version\", project.version)\n");
        sb.append("\tfilesMatching(\"fabric.mod.json\") {\n");
        sb.append("\t\texpand(mapOf(\"version\" to project.version))\n");
        sb.append("\t}\n}\n\n");
        sb.append("tasks.withType<JavaCompile>().configureEach {\n");
        sb.append("\toptions.release = 21\n}\n\n");
        sb.append("java {\n\twithSourcesJar()\n\tsourceCompatibility = JavaVersion.VERSION_21\n");
        sb.append("\ttargetCompatibility = JavaVersion.VERSION_21\n}\n");
        return sb.toString();
    }

    private static String fabricModJson(TemplateRequest req, String modId, String modName,
                                        String packageName, String className) {
        String mainEntry = packageName + "." + className;
        StringBuilder ep = new StringBuilder();
        ep.append("\t\t\"main\": [\n\t\t\t\"").append(mainEntry).append("\"\n\t\t]");
        if (req.splitSources()) {
            ep.append(",\n\t\t\"client\": [\n\t\t\t\"")
                    .append(packageName).append(".").append(className).append("Client\"\n\t\t]");
        }
        if (req.dataGen()) {
            ep.append(",\n\t\t\"fabric-datagen\": [\n\t\t\t\"")
                    .append(packageName).append(".").append(className).append("DataGenerator\"\n\t\t]");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("\t\"schemaVersion\": 1,\n");
        sb.append("\t\"id\": \"").append(modId).append("\",\n");
        sb.append("\t\"version\": \"${version}\",\n");
        sb.append("\t\"name\": \"").append(escapeJson(modName)).append("\",\n");
        sb.append("\t\"description\": \"Generated by Black Launcher\",\n");
        sb.append("\t\"authors\": [\"Me!\"],\n");
        sb.append("\t\"contact\": {},\n");
        sb.append("\t\"license\": \"CC0-1.0\",\n");
        sb.append("\t\"icon\": \"assets/").append(modId).append("/icon.png\",\n");
        sb.append("\t\"environment\": \"*\",\n");
        sb.append("\t\"entrypoints\": {\n").append(ep).append("\n\t},\n");
        sb.append("\t\"mixins\": [\n\t\t\"").append(modId).append(".mixins.json\"\n\t],\n");
        if (req.kotlin()) {
            sb.append("\t\"languageAdapters\": {\n");
            sb.append("\t\t\"kotlin\": \"net.fabricmc.language.kotlin.KotlinAdapter\"\n");
            sb.append("\t},\n");
        }
        sb.append("\t\"depends\": {\n");
        sb.append("\t\t\"fabricloader\": \">=0.16.0\",\n");
        sb.append("\t\t\"minecraft\": \"~").append(req.minecraftVersion().trim()).append("\",\n");
        sb.append("\t\t\"java\": \">=21\",\n");
        sb.append("\t\t\"fabric-api\": \"*\"");
        if (req.kotlin()) {
            sb.append(",\n\t\t\"fabric-language-kotlin\": \"*\"");
        }
        sb.append("\n\t}\n}\n");
        return sb.toString();
    }

    private static String mixinsJson(String packageName) {
        return """
                {
                \t"required": true,
                \t"package": "%s.mixin",
                \t"compatibilityLevel": "JAVA_21",
                \t"mixins": [],
                \t"injectors": {
                \t\t"defaultRequire": 1
                \t}
                }
                """.formatted(packageName);
    }

    private static String javaMain(String pkg, String className, String modId) {
        return """
                package %s;
                
                import net.fabricmc.api.ModInitializer;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                
                public class %s implements ModInitializer {
                \tpublic static final String MOD_ID = "%s";
                \tpublic static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
                
                \t@Override
                \tpublic void onInitialize() {
                \t\tLOGGER.info("Hello Fabric world!");
                \t}
                }
                """.formatted(pkg, className, modId);
    }

    private static String kotlinMain(String pkg, String className, String modId) {
        return """
                package %s
                
                import net.fabricmc.api.ModInitializer
                import org.slf4j.LoggerFactory
                
                object %s : ModInitializer {
                \tconst val MOD_ID = "%s"
                \tprivate val logger = LoggerFactory.getLogger(MOD_ID)
                
                \toverride fun onInitialize() {
                \t\tlogger.info("Hello Fabric world!")
                \t}
                }
                """.formatted(pkg, className, modId);
    }

    private static String javaClient(String pkg, String className, String modId) {
        return """
                package %s;
                
                import net.fabricmc.api.ClientModInitializer;
                
                public class %s implements ClientModInitializer {
                \t@Override
                \tpublic void onInitializeClient() {
                \t\t// Client init for %s
                \t}
                }
                """.formatted(pkg, className, modId);
    }

    private static String kotlinClient(String pkg, String className, String modId) {
        return """
                package %s
                
                import net.fabricmc.api.ClientModInitializer
                
                object %s : ClientModInitializer {
                \toverride fun onInitializeClient() {
                \t\t// Client init for %s
                \t}
                }
                """.formatted(pkg, className, modId);
    }

    private static String javaDataGen(String pkg, String className) {
        return """
                package %s;
                
                import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
                import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
                
                public class %s implements DataGeneratorEntrypoint {
                \t@Override
                \tpublic void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
                \t\tFabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
                \t}
                }
                """.formatted(pkg, className);
    }

    private static String kotlinDataGen(String pkg, String className) {
        return """
                package %s
                
                import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint
                import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator
                
                object %s : DataGeneratorEntrypoint {
                \toverride fun onInitializeDataGenerator(fabricDataGenerator: FabricDataGenerator) {
                \t\tfabricDataGenerator.createPack()
                \t}
                }
                """.formatted(pkg, className);
    }

    private static String gitignore() {
        return """
                .gradle/
                build/
                out/
                run/
                bin/
                .idea/
                *.iml
                *.ipr
                *.iws
                .vscode/
                *.log
                src/main/generated/
                """;
    }

    private static String readme(String modName, String modId, boolean wrapperOk) {
        String wrapperNote = wrapperOk
                ? ""
                : "\n> **Note:** `gradle/wrapper/gradle-wrapper.jar` could not be downloaded. "
                + "Run `gradle wrapper` once you have Gradle installed, or copy the jar from "
                + "[fabric-example-mod](https://github.com/FabricMC/fabric-example-mod).\n";
        return """
                # %s
                
                Fabric mod template generated by **Black Launcher**.
                
                License of this template: [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/).
                %s
                ## Open in IDE
                
                1. Install JDK 21+
                2. Open this folder in IntelliJ IDEA / VS Code
                3. Import as Gradle project
                4. Run `./gradlew build` (or `gradlew.bat build` on Windows)
                
                Mod ID: `%s`
                """.formatted(modName, wrapperNote, modId);
    }

    private static void writeText(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Cleanup failed for {}", root, e);
        }
    }
}
