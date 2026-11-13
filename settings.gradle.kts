pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

rootProject.name = "multipay-sdk-android"
include(":multipay-core")

// O modulo Android so entra no build se houver Android SDK (ANDROID_HOME ou local.properties com sdk.dir).
// O nucleo (multipay-core) e Kotlin/JVM puro e compila/testa em qualquer maquina.
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":multipay-android")
    if (providers.gradleProperty("withExample").isPresent) include(":example")
}
