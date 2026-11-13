buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.gradleup.shadow:shadow-gradle-plugin:8.3.11")
    }
}

plugins {
    `java-library`
}

group = "dev.mooner.neonjs"
version = "0.1.4"

repositories {
    mavenCentral()
}

val neonBundleLibraries = configurations.create("neonBundleLibraries") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
}

dependencies {
    compileOnly("com.ibm.icu:icu4j:70.1")
    compileOnly("org.ow2.asm:asm-util:9.9.1")

    add(neonBundleLibraries.name, "org.ow2.asm:asm-util:9.9.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

val neonRuntimeBundle = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("neonRuntimeBundle") {
    dependsOn(tasks.named("classes"))
    archiveBaseName.set("neonjs-runtime")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    from(sourceSets.main.get().output)
    configurations = listOf(neonBundleLibraries)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    exclude(
        "META-INF/MANIFEST.MF",
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "module-info.class",
        "META-INF/versions/**/module-info.class",
    )
    mergeServiceFiles()
    relocate("org.objectweb.asm", "io.github.kltyton.kltytonui.internal.asm")

    manifest {
        attributes(
            "Automatic-Module-Name" to "io.github.kltyton.kltytonui.neon.runtime",
            "FMLModType" to "GAMELIBRARY",
            "Implementation-Version" to project.version.toString(),
        )
    }

}

listOf("apiElements", "runtimeElements").forEach { configurationName ->
    configurations.named(configurationName) {
        outgoing.artifacts.clear()
        outgoing.artifact(neonRuntimeBundle)
    }
}

tasks.named("assemble") {
    dependsOn(neonRuntimeBundle)
}

tasks.named<org.gradle.api.tasks.bundling.Jar>("jar") {
    enabled = false
}
