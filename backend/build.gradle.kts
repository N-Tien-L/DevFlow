plugins {
    java
    id("org.springframework.boot") version "3.4.5" apply false
}

allprojects {
    group = "io.devflow"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // Java 17: minimum for Spring Boot 3.x and matches the team's installed JDK.
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

/**
 * Build-time enforcement of the module boundary rule (ARCHITECTURE.md Section 10):
 * no module may depend on another module's -impl project. Allowed dependencies are:
 *   :common            -> nothing
 *   :X-api             -> :common only
 *   :X-impl            -> :common, :X-api, and other modules' -api projects
 *   :app               -> everything (it is the bootstrap/deployable)
 * Fails the build if anyone adds an impl -> impl dependency line.
 */
val verifyModuleBoundaries by tasks.registering {
    description = "Verifies ARCHITECTURE.md Section 10 module boundaries in Gradle dependencies."
    group = "verification"
    doLast {
        val implPaths = subprojects.filter { it.name.endsWith("-impl") }.map { it.path }.toSet()
        subprojects.filter { it.name != "app" }.forEach { module ->
            module.configurations.forEach { config ->
                config.dependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                    .forEach { dep ->
                        val target = dep.dependencyProject.path
                        require(target !in implPaths) {
                            "Module boundary violation: '${module.path}' depends on '$target'. " +
                                "Depend on its -api project instead (ARCHITECTURE.md Section 10)."
                        }
                    }
            }
        }
    }
}

subprojects {
    tasks.named("check") {
        dependsOn(verifyModuleBoundaries)
    }
}
