package io.github.mangi.flymefreeform.platform.coloros

import java.io.InterruptedIOException
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** 从宿主调用关系定位，不以 Android SDK 或混淆名作为版本选择依据。 */
internal object SidebarDexQuery {
    const val RULE_VERSION = "sidebar-v2-dexkit-2.3.0"
    private const val UTILS = "com.oplus.smartsidebar.utils"
    private const val SIZE = "com.oplus.smartsidebar.panelview.edgepanel.utils.MainPanelSizeHelper"
    private const val MAIN = "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView"
    private const val PLATFORM_BLUR = "$UTILS.SidebarPlatformBlurHelper"

    @JvmOverloads
    fun find(bridge: DexKitBridge, report: (String, Throwable?) -> Unit = { _, _ -> }): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val gap = bridge.getClassData(SIZE)?.methods?.singleOrNull { it.name == "getPanelGap" && it.paramCount == 0 }
            ?: throw NoSuchMethodException("SIDEBAR_DEX_GAP_NOT_UNIQUE")
        val predicates = gap.invokes.filter { it.paramCount == 0 && it.returnTypeName == "boolean" }
            .distinctBy { it.descriptor }
        require(predicates.size == 2) { "SIDEBAR_DEX_ORIENTATION_NOT_UNIQUE" }
        // 两种谓词只在首个分支相反；同时约束实际调用者和折叠/旋转/平板调用图。
        val tail = listOf("invoke-virtual", "move-result", "if-nez", "invoke-virtual", "move-result", "if-nez", "const/4", "return", "const/4", "return")
        val landscape = predicates.singleOrNull { it.opNames.map { opcode -> if (opcode == "goto") "return" else opcode } == listOf("invoke-virtual", "move-result", "if-nez") + tail }
            ?: throw NoSuchMethodException("SIDEBAR_DEX_LANDSCAPE_NOT_UNIQUE")
        val portrait = predicates.singleOrNull { it.opNames.map { opcode -> if (opcode == "goto") "return" else opcode } == listOf("invoke-virtual", "move-result", "if-eqz") + tail }
            ?: throw NoSuchMethodException("SIDEBAR_DEX_PORTRAIT_NOT_UNIQUE")
        require(landscape.className == portrait.className) { "SIDEBAR_DEX_ORIENTATION_OWNER_MISMATCH" }
        val dependencies = landscape.invokes.map { it.descriptor }.toSet()
        require(dependencies.size == 3 && dependencies == portrait.invokes.map { it.descriptor }.toSet()) {
            "SIDEBAR_DEX_ORIENTATION_DEPENDENCIES_MISMATCH"
        }
        val owner = checkNotNull(landscape.declaredClass)
        val hover = owner.methods.findMethod {
            matcher {
                paramTypes("boolean")
                returnType = "boolean"
                usingEqStrings("isHoverMode : is fold state,not the hover move", "ResourceUtil")
            }
        }.singleOrNull() ?: throw NoSuchMethodException("SIDEBAR_DEX_HOVER_NOT_UNIQUE")
        val reads = gap.usingFields.map { it.field }.distinctBy { it.descriptor }
        val resource = reads.singleOrNull { it.typeName == owner.name && Modifier.isStatic(it.modifiers) }
            ?: throw NoSuchFieldException("SIDEBAR_DEX_RESOURCE_OWNER_NOT_UNIQUE")
        result["resource"] = resource.descriptor
        result["landscape"] = landscape.descriptor
        result["portrait"] = portrait.descriptor
        result["hover"] = hover.descriptor

        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SIDEBAR_DEX_CANCELLED")
        try {
            result.putAll(findShadow(bridge))
        } catch (exception: ReflectiveOperationException) {
            report("SIDEBAR_DEX_SHADOW_UNAVAILABLE", exception)
            result["shadowKind"] = "unavailable"
        } catch (exception: IllegalArgumentException) {
            report("SIDEBAR_DEX_SHADOW_UNAVAILABLE", exception)
            result["shadowKind"] = "unavailable"
        }

        val unit = bridge.findMethod {
            searchPackages("kotlin")
            matcher { name = "toString"; paramTypes(); returnType = "java.lang.String"; usingEqStrings("kotlin.Unit") }
        }.singleOrNull()?.declaredClass ?: throw NoSuchMethodException("SIDEBAR_DEX_UNIT_NOT_UNIQUE")
        result["unit"] = unit.fields.singleOrNull {
            it.typeName == unit.name && Modifier.isStatic(it.modifiers)
        }?.descriptor ?: throw NoSuchFieldException("SIDEBAR_DEX_UNIT_INSTANCE_NOT_UNIQUE")
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SIDEBAR_DEX_CANCELLED")
        try {
            result.putAll(findBlur(bridge))
        } catch (exception: ReflectiveOperationException) {
            report("SIDEBAR_DEX_BLUR_UNAVAILABLE", exception)
            result["blurKind"] = "unavailable"
        } catch (exception: IllegalArgumentException) {
            report("SIDEBAR_DEX_BLUR_UNAVAILABLE", exception)
            result["blurKind"] = "unavailable"
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SIDEBAR_DEX_CANCELLED")
        return result
    }

    private fun findShadow(bridge: DexKitBridge): Map<String, String> {
        val nativeCalls = bridge.getClassData(MAIN)?.methods?.flatMap { it.invokes }
            ?.distinctBy { it.descriptor } ?: throw NoSuchMethodException("SIDEBAR_DEX_MAIN_MISSING")
        val shadowUpdate = nativeCalls.filter {
            it.className.startsWith("$UTILS.") && it.paramTypeNames == listOf("android.view.View", "float") &&
                it.returnTypeName == "void" && Modifier.isStatic(it.modifiers)
        }.filter { method ->
            method.invokes.any { it.descriptor == "Landroid/view/View;->setElevation(F)V" } ||
                // 新结构将阴影参数打包后交给同类方法；只沿这一层实际调用检查。
                method.invokes.any { delegate ->
                    delegate.className == method.className && Modifier.isStatic(delegate.modifiers) &&
                        delegate.returnTypeName == "void" && delegate.paramCount == 3 &&
                        delegate.paramTypeNames.first() == "android.view.View" &&
                        delegate.paramTypeNames[1].startsWith(method.className + "$") &&
                        delegate.paramTypeNames.last() == "float" &&
                        delegate.invokes.any { it.descriptor == "Landroid/view/View;->setElevation(F)V" }
                }
        }.unique("SHADOW_UPDATE")
        val shadowSetup = checkNotNull(shadowUpdate.declaredClass).methods.filter {
            it.className == shadowUpdate.className && it.paramTypeNames == listOf("android.view.View") &&
                it.returnTypeName == "void" && Modifier.isStatic(it.modifiers) &&
                it.callers.any { caller -> caller.className == MAIN || caller.className.startsWith(MAIN + "$") }
        }.unique("SHADOW_SETUP")
        return mapOf("shadowKind" to "available", "shadowSetup" to shadowSetup.descriptor, "shadowUpdate" to shadowUpdate.descriptor)
    }

    private fun findBlur(bridge: DexKitBridge): Map<String, String> {
        val platform = bridge.getClassData(PLATFORM_BLUR)
        if (platform != null) {
            val attach = platform.methods.filter {
                it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames in listOf(
                        listOf("android.view.View", "boolean", "boolean"),
                        listOf("android.view.View", "boolean", "boolean", "int"),
                    ) && "applyTo: blur not supported" in it.usingStrings
            }.unique("BLUR_ATTACH")
            val update = platform.methods.findMethod {
                matcher { paramTypes("float"); returnType = "void"; addInvoke("Landroid/view/View;->invalidate()V") }
            }.unique("BLUR_UPDATE")
            val close = platform.methods.findMethod {
                matcher { paramTypes(); returnType = "void"; usingEqStrings("SidebarPlatformBlur", "release") }
            }.unique("BLUR_CLOSE")
            require(listOf(attach, update, close).none { Modifier.isStatic(it.modifiers) })
            val defaults = if (attach.paramCount == 4) platform.methods.filter {
                Modifier.isStatic(it.modifiers) && it.modifiers and 0x1000 != 0 &&
                    it.returnTypeName == "boolean" && it.paramTypeNames == listOf(
                        platform.name, "android.view.View", "boolean", "boolean", "int", "int", "java.lang.Object",
                    ) && it.invokes.any { call -> call.descriptor == attach.descriptor }
            }.unique("BLUR_ATTACH_DEFAULTS") else null
            return mapOf(
                "blurKind" to if (defaults == null) "platform" else "platform-default",
                "blurAttach" to (defaults ?: attach).descriptor,
                "blurUpdate" to update.descriptor, "blurClose" to close.descriptor,
            )
        }
        val attach = bridge.findMethod {
            searchPackages(UTILS)
            matcher {
                paramTypes("android.view.View", "boolean", "boolean")
                returnType = "com.oplus.view.ViewRootManager"
                usingEqStrings("initBlurBackGroundView  failed, case view not attached")
            }
        }.unique("BLUR_ATTACH")
        val owner = checkNotNull(attach.declaredClass)
        val update = owner.methods.findMethod {
            matcher {
                paramTypes("android.view.View", "com.oplus.view.ViewRootManager", "float")
                returnType = "void"
                addInvoke("Lcom/oplus/view/ViewRootManager;->setBlurRadius(I)V")
            }
        }.unique("BLUR_UPDATE")
        val toggles = listOf("getBlurToggle: ", "getGaussianLevel: ", "getGaussianSupport: ").map { text ->
            owner.methods.findMethod { matcher { paramTypes(); returnType = "boolean"; usingEqStrings(text) } }.unique("BLUR_SUPPORT_COMPONENT").descriptor
        }.toSet()
        val supports = owner.methods.filter {
            it.paramCount == 0 && it.returnTypeName == "boolean" &&
                it.invokes.map { call -> call.descriptor }.toSet().containsAll(toggles)
        }.unique("BLUR_SUPPORT")
        require(listOf(attach, update, supports).all { Modifier.isStatic(it.modifiers) })
        return mapOf("blurKind" to "legacy", "blurAttach" to attach.descriptor, "blurUpdate" to update.descriptor, "blurSupports" to supports.descriptor)
    }

    private fun List<MethodData>.unique(code: String): MethodData = singleOrNull()
        ?: throw NoSuchMethodException("SIDEBAR_DEX_${code}_NOT_UNIQUE: candidates=$size")
}
