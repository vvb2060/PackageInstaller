plugins {
    id("com.android.library")
}

android {
    namespace = "stub"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    //noinspection GradleDependency
    compileOnly("androidx.annotation:annotation:1.3.0")
}
