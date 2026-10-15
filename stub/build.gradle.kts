plugins {
    id("com.android.library")
}

android {
    enableKotlin = false
    namespace = "stub"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

dependencies {
    //noinspection GradleDependency
    compileOnly("androidx.annotation:annotation:1.3.0")
}
