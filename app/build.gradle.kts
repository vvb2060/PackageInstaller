import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Opcodes.POP2
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.vvb2060.packageinstaller"
    defaultConfig {
        versionCode = 5
        versionName = "1.4"
        optimization {
            keepRules {
                ignoreFromAllExternalDependencies(true)
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation("androidx.fragment:fragment:1.8.8")
    implementation("androidx.lifecycle:lifecycle-livedata:2.9.2")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
}

androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        ClassVisitorFactory::class.java, InstrumentationScope.PROJECT
    ) {}
    variant.instrumentation.transformClassesWith(
        ZipStreamClassVisitorFactory::class.java,
        InstrumentationScope.ALL
    ) {}
}

abstract class ClassVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return ClassRemapper(nextClassVisitor, object : Remapper() {
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
        return classData.className.startsWith("io.github.vvb2060.packageinstaller.model.")
    }
}

abstract class ZipStreamClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return object : ClassVisitor(ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<String>?
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "handleSizesAndCrc") {
                    return HandleSizesAndCrcMethodVisitor(mv)
                }
                return mv
            }
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className == "org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream"
    }
}


class HandleSizesAndCrcMethodVisitor(methodVisitor: MethodVisitor) :
    MethodVisitor(ASM9, methodVisitor) {
    private var state = 0

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        if (opcode == INVOKEVIRTUAL && owner == "org/apache/commons/compress/archivers/zip/ZipArchiveEntry") {
            if (name == "setCrc" && descriptor == "(J)V") {
                if (state == 0) {
                    state = 1
                } else if (state == 1) {
                    state = 2
                    super.visitInsn(POP2) // crc
                    super.visitInsn(POP)  // entry
                    return
                }
            }
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
