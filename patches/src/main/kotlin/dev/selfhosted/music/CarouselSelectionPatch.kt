package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CAROUSEL_TELEMETRY = "Ldev/selfhosted/music/Telemetry;"

/**
 * 8.40.54 two-row item selection: qgb associates its item View with a pss
 * command callback through psu.b. pss.a dispatches that same callback's endpoint.
 * Runtime emits only mapped selections under the observed carousel/header tree.
 * No shelf label or Quick Play attribution is inferred by this patch.
 */
@Suppress("unused")
val carouselSelectionPatch = bytecodePatch(
    name = "Carousel selection telemetry",
    description = "Exports two-row carousel song selections with their observed shelf heading. Quick picks coverage depends on the live renderer.",
    use = false,
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)
    apply {
        fun method(type: String, name: String, parameters: List<String>) =
            classDefs[type]?.let { classDefs.getOrReplaceMutable(it) }?.methods?.singleOrNull {
                it.name == name && it.parameterTypes == parameters && it.returnType == "V" && it.implementation != null
            } ?: throw PatchException("Carousel telemetry: missing unique $type->$name")
        val bind = method("Lqgb;", "fz", listOf("Lbccu;", "Ljava/lang/Object;"))
        val dispatch = method("Lpss;", "a", emptyList())
        val bindingInstructions = bind.implementation!!.instructions.toList()
        val bindingSites = bindingInstructions.indices.filter { index ->
            val ref = (bindingInstructions[index] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.toString() == "Lpsu;->b(Lpst;)V"
        }
        // fz also binds a separate bucw overlay command later in the method.
        // Select the primary bufj.h item callback, never the overlay by position alone.
        if (bindingSites.size != 2) throw PatchException("Carousel telemetry: expected primary and overlay bindings")
        val primarySites = bindingSites.filter { index ->
            val call = bindingInstructions[index] as? FiveRegisterInstruction
            val result = bindingInstructions.getOrNull(index - 1) as? OneRegisterInstruction
            val factory = bindingInstructions.getOrNull(index - 2) as? FiveRegisterInstruction
            val reference = (bindingInstructions.getOrNull(index - 2) as? ReferenceInstruction)?.reference as? MethodReference
            call?.registerC == 3 && call.registerD == 11 &&
                bindingInstructions[index - 1].opcode == Opcode.MOVE_RESULT_OBJECT && result?.registerA == 11 &&
                reference?.toString() == "Lpss;->b(Lancv;Lapzw;Lbmme;Ljava/util/Map;)Lpss;" &&
                factory?.registerC == 8 && factory.registerD == 11 && factory.registerE == 12 && factory.registerF == 13
        }
        if (primarySites.size != 1) throw PatchException("Carousel telemetry: ambiguous primary item callback")
        val bindingIndex = primarySites.single()
        val overlay = bindingInstructions[bindingSites.single { it != bindingIndex }] as? FiveRegisterInstruction
        if (overlay?.registerC != 5 || overlay.registerD != 4) {
            throw PatchException("Carousel telemetry: changed secondary overlay binding")
        }
        val factories = bindingInstructions.indices.filter { index ->
            ((bindingInstructions[index] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() ==
                "Lpsv;->a(Landroid/view/View;[BLapzw;)Lpsu;" && index < bindingIndex
        }
        if (factories.size != 1 || (bindingInstructions[factories.single()] as? FiveRegisterInstruction)?.registerC != 5) {
            throw PatchException("Carousel telemetry: changed item View binding")
        }
        val endpointRead = bindingInstructions.subList(factories.single(), bindingIndex).filter {
            (it as? ReferenceInstruction)?.reference?.toString() == "Lbufj;->h:Lbmme;"
        }
        if (endpointRead.size != 1 || (endpointRead.single() as? OneRegisterInstruction)?.registerA != 12) {
            throw PatchException("Carousel telemetry: missing primary item endpoint")
        }
        val dispatchInstructions = dispatch.implementation!!.instructions.toList()
        val dispatchSites = dispatchInstructions.indices.filter { index ->
            ((dispatchInstructions[index] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() ==
                "Lancv;->c(Lbmme;Ljava/util/Map;)V"
        }
        if (dispatchSites.size != 1) throw PatchException("Carousel telemetry: ambiguous command dispatch")
        val dispatchIndex = dispatchSites.single()
        val dispatchCall = dispatchInstructions[dispatchIndex] as? FiveRegisterInstruction
        if (dispatchCall?.registerD != 1 || dispatch.implementation!!.registerCount != 5) {
            throw PatchException("Carousel telemetry: changed endpoint registers")
        }
        val extension = classDefs[CAROUSEL_TELEMETRY]?.let { classDefs.getOrReplaceMutable(it) }
            ?: throw PatchException("Carousel telemetry: extension missing")
        listOf(
            "bindCarouselItem" to listOf("Ljava/lang/Object;", "Landroid/view/View;"),
            "onCarouselDispatch" to listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        ).forEach { (name, parameters) ->
            if (extension.methods.count { it.name == name && it.parameterTypes == parameters && it.returnType == "V" &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) } != 1) {
                throw PatchException("Carousel telemetry: missing runtime callback $name")
            }
        }
        bind.addInstruction(bindingIndex, "invoke-static {v11, v5}, $CAROUSEL_TELEMETRY->bindCarouselItem(Ljava/lang/Object;Landroid/view/View;)V")
        dispatch.addInstruction(dispatchIndex, "invoke-static {p0, v1}, $CAROUSEL_TELEMETRY->onCarouselDispatch(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
}
