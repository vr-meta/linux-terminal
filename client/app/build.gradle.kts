plugins {
    id("com.android.application")
}

// The headset half: an ordinary 2D Android app. Horizon OS shows it as a window
// beside every other app, which is the whole point — placement, resizing and
// coexistence with a browser come from the shell instead of being reimplemented.
android {
    namespace = "dev.butschster.linuxterminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.butschster.linuxterminal"
        minSdk = 32
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    // Release builds are signed with a key kept outside the repository, and CI
    // gets the same one from a secret.
    //
    // This is not ceremony. Android refuses to install an update signed by a
    // different key than the installed copy, and a debug keystore is generated
    // per machine — so without a stable key every release would have to be
    // uninstalled before the next one could be installed.
    val keystore = file(
        providers.environmentVariable("ANDROID_KEYSTORE").orNull
            ?: "${System.getProperty("user.home")}/.config/linux-terminal/release.keystore"
    )
    val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull

    signingConfigs {
        if (keystore.exists() && keystorePassword != null) {
            create("release") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                    ?: "linux-terminal"
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
                    ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falling back to the debug key keeps a fork buildable; the APK it
            // produces simply cannot upgrade an install signed by the real one.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }

        // A build for wearing, not for shipping: the debug variant's contents,
        // signed with the release key.
        //
        // It exists because Android refuses an update signed by a different key, and
        // the headset already carries a properly signed install. The alternative was
        // to uninstall it, which throws away the paired servers and their tokens and
        // means pairing every machine again to look at a change.
        create("concept") {
            initWith(getByName("debug"))
            isDebuggable = true
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The vendored emulator is upstream code; its warnings are not ours to fix.
        disable += setOf("UnusedResources")
    }
}
