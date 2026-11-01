import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

plugins {
    id("com.android.application")
}

android {
    enableKotlin = false
    namespace = "io.github.vvb2060.packageinstaller"
    defaultConfig {
        versionCode = 12
        versionName = "2.0"
    }
    buildTypes {
        release {
            vcsInfo.include = false
            signingConfig = signingConfigs["debug"]
            optimization {
                enable = true
                keepRules {
                    ignoreFromAllExternalDependencies = true
                    includeDefault = false
                }
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        resources {
            excludes += "**"
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    compileOnly(projects.stub)
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.11.0")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}

androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        ClassVisitorFactory::class.java, InstrumentationScope.PROJECT
    ) {}
}

abstract class ClassVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return ClassRemapper(nextClassVisitor, object : Remapper(ASM9) {
            override fun map(name: String): String {
                val index = name.indexOf('$')
                if (index != -1) {
                    return map(name.substring(0, index)) + name.substring(index)
                }
                if (name.endsWith("_rename")) {
                    return name.substring(0, name.length - 7)
                }
                return name
            }
        })
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className.startsWith("io.github.vvb2060.packageinstaller.core.")
    }
}
