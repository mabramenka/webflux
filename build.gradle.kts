import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    alias(libs.plugins.lombok)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
    alias(libs.plugins.dependency.check)
}

group = "dev.abramenka"
version = providers
    .gradleProperty("revision")
    .orElse(providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() })
    .get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.webclient)
    implementation(libs.micrometer.context.propagation)
    implementation(libs.springdoc.openapi.starter.webflux.ui)

    lombok(platform(SpringBootPlugin.BOM_COORDINATES))
    lombok(libs.lombok)

    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor(libs.spring.boot.configuration.processor)

    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.reactor.test)

    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:deprecation",
            "-Xlint:removal",
            "-Werror"
        )
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        error("NullAway")
        option("NullAway:AnnotatedPackages", "dev.abramenka")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
        option("NullAway:ExhaustiveOverride", "true")
        option("NullAway:CheckOptionalEmptiness", "true")
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        importOrder()
    }

    format("gradleKts") {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target("*.md", ".gitignore", "gradle/**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencyCheck {
    failBuildOnCVSS = 7.0F
    formats = listOf("HTML", "JSON")
    scanConfigurations = listOf("runtimeClasspath")
    nvd {
        apiKey = System.getenv("NVD_API_KEY")
    }
    analyzers {
        assemblyEnabled = false
    }
}

tasks.register("verifyBoot4Classpath") {
    description = "Fails when Boot 3, Spring Framework 6, or Jackson 2 artifacts are present on runtime classpaths."
    group = "verification"

    val checkedConfigurations = listOf(
        configurations.named("runtimeClasspath"),
        configurations.named("testRuntimeClasspath")
    )

    checkedConfigurations.forEach { configurationProvider ->
        inputs.files(configurationProvider)
    }

    val forbiddenComponents = providers.provider {
        checkedConfigurations
            .flatMap { configurationProvider ->
                val configuration = configurationProvider.get()
                configuration.incoming.resolutionResult.allComponents
                    .filter { component ->
                        val id = component.moduleVersion
                        id != null && (isSpringBoot3(id) || isSpringFramework6(id) || isJackson2(id))
                    }
                    .map { component ->
                        val id = component.moduleVersion!!
                        "${configuration.name}: ${id.group}:${id.name}:${id.version}"
                    }
            }
            .distinct()
            .sorted()
    }

    inputs.property("forbiddenComponents", forbiddenComponents)

    doLast {
        val components = forbiddenComponents.get()
        if (components.isNotEmpty()) {
            throw GradleException(
                "Boot 4 / Spring Framework 7 classpath verification failed:\n - ${components.joinToString("\n - ")}"
            )
        }
    }
}

tasks.register("printStackVersions") {
    description = "Prints the stack versions from the version catalog. Use to refresh README."
    group = "documentation"

    val stack = mapOf(
        "Java" to libs.versions.java.get(),
        "Spring Boot" to libs.versions.spring.boot.get(),
        "JaCoCo" to libs.versions.jacoco.get(),
        "Error Prone" to libs.versions.errorprone.asProvider().get(),
        "NullAway" to libs.versions.nullaway.get(),
        "Spotless" to libs.versions.spotless.get(),
        "SonarQube" to libs.versions.sonarqube.get(),
        "OWASP Dependency-Check" to libs.versions.dependency.check.get(),
    )

    doLast {
        stack.forEach { (name, ver) -> println("- $name $ver") }
    }
}

tasks.register("securityCheck") {
    description = "Runs OWASP Dependency Check. Set NVD_API_KEY before using it to avoid slow NVD API updates."
    group = "verification"
    dependsOn(tasks.named("dependencyCheckAnalyze"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyBoot4Classpath"))
    dependsOn(tasks.named("spotlessCheck"))
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

sonar {
    properties {
        property("sonar.projectKey", "mabramenka_webflux")
        property("sonar.organization", "mabramenka")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${layout.buildDirectory.get().asFile}/reports/jacoco/test/jacocoTestReport.xml"
        )
    }
}

tasks.named("sonar") {
    dependsOn(tasks.named("jacocoTestReport"))
}

fun isSpringBoot3(id: ModuleVersionIdentifier): Boolean =
    id.group == "org.springframework.boot" && id.version.startsWith("3.")

fun isSpringFramework6(id: ModuleVersionIdentifier): Boolean =
    id.group == "org.springframework" && id.name.startsWith("spring-") && id.version.startsWith("6.")

fun isJackson2(id: ModuleVersionIdentifier): Boolean {
    if (!id.group.startsWith("com.fasterxml.jackson")) return false
    if (id.group == "com.fasterxml.jackson.core" && id.name == "jackson-annotations") return false
    val springdocJacksonAllowList = setOf(
        "com.fasterxml.jackson:jackson-bom",
        "com.fasterxml.jackson.core:jackson-core",
        "com.fasterxml.jackson.core:jackson-databind",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
        "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
    )
    return "${id.group}:${id.name}" !in springdocJacksonAllowList
}
