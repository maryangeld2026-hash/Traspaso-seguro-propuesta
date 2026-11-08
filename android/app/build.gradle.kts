plugins {
    id("com.android.application")
}

// El número de versión lo pone GitHub Actions (número de compilación); en local vale 1.
val numero = (System.getenv("VERSION_CODE") ?: "1").toInt()

// Firma: si existe android/keystore/traspaso.jks (GitHub lo crea desde un secreto), se usa;
// si no, se firma con la clave de depuración. Usar siempre la misma clave permite actualizar la app sin desinstalarla.
val almacen = rootProject.file("keystore/traspaso.jks")

android {
    namespace = "co.traspasoseguro.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "co.traspasoseguro.app"
        minSdk = 26
        targetSdk = 35
        versionCode = numero
        versionName = "1.0.$numero"
    }

    signingConfigs {
        if (almacen.exists()) {
            create("propia") {
                storeFile = almacen
                storePassword = System.getenv("KS_PASS") ?: ""
                keyAlias = System.getenv("KS_ALIAS") ?: "traspaso"
                keyPassword = System.getenv("KS_PASS") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (almacen.exists()) signingConfigs.getByName("propia") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
