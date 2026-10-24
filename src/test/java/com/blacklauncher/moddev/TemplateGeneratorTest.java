package com.blacklauncher.moddev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateGeneratorTest {

    @TempDir
    Path temp;

    @Test
    void generateWritesFabricModJsonWithoutNetwork() throws Exception {
        TemplateRequest req = new TemplateRequest(
                "Example Mod",
                "example-mod",
                "com.example.mod",
                "1.21.4",
                false,
                false,
                false,
                false,
                "1.21.4+build.8",
                "0.16.10",
                "0.119.2+1.21.4"
        );
        TemplateGenerator generator = new TemplateGenerator((FabricMetaClient) null);
        Path project = temp.resolve("example-mod");
        generator.generate(req, project);

        Path fabricJson = project.resolve("src/main/resources/fabric.mod.json");
        assertTrue(Files.isRegularFile(fabricJson), "fabric.mod.json should exist");
        String json = Files.readString(fabricJson);
        assertTrue(json.contains("\"id\": \"example-mod\""));
        assertTrue(json.contains("com.example.mod.ExampleMod"));
        assertTrue(Files.isRegularFile(project.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(project.resolve("gradle.properties")));
        assertTrue(Files.isRegularFile(
                project.resolve("src/main/java/com/example/mod/ExampleMod.java")));
    }

    @Test
    void classNameFromModName() {
        org.junit.jupiter.api.Assertions.assertEquals("ExampleMod",
                TemplateGenerator.toClassName("Example Mod", "template-mod"));
        org.junit.jupiter.api.Assertions.assertEquals("CoolStuffMod",
                TemplateGenerator.toClassName("Cool Stuff", "cool-stuff"));
    }
}
