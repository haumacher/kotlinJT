plugins {
    kotlin("multiplatform") version "1.9.24"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "de.haumacher.kotlinjt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Byte I/O seam: file access in commonMain without platform types (see DESIGN.md).
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.5")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
            }
        }
        val jsMain by getting {
            dependencies {
                // zlib `actual` for Kotlin/JS (see DESIGN.md).
                implementation(npm("pako", "2.1.0"))
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
