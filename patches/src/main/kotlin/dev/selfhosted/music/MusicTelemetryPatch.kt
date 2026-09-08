package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Ldev/selfhosted/music/Telemetry;"

// Fingerprint anchors adapted from inotia00/revanced-patches (GPL-3.0),
// revision 54ce1d4808b12903602a1a0d9a721ee835093c38:
// music/video/information/Fingerprints.kt, shared/Fingerprints.kt,
// music/utils/extension/hooks/ApplicationInitHook.kt.
// All required anchors must match uniquely on the supported APK.
@Suppress("unused")
val musicTelemetryPatch = bytecodePatch(
    name = "Self-hosted Music telemetry",
    description = "Exports track, progress and rating command events. Configure the collector in Settings > Listen telemetry.",
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    extendWith("extensions/telemetry.rve")

    dependsOn(musicTelemetrySettingsPatch)

    apply { applyMusicTelemetry() }
}

// Keep instrumentation independently exercisable with synthetic DEX fixtures;
// the patch application also merges the Android extension before calling this.
internal fun BytecodePatchContext.applyMusicTelemetry() {
    // Resolve every required target before mutation. Ambiguity is an error.
    val init = uniqueMethod("application onCreate") {
        name == "onCreate" && parameterTypes.isEmpty() && returnType == "V" &&
            !AccessFlags.STATIC.isSet(accessFlags) && hasString("activity") &&
            implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name == "getRunningAppProcesses" &&
                    ref.definingClass == "Landroid/app/ActivityManager;"
            }
    }
    // 8.40.54 delegates Application.onCreate to Lisz.f(). Hook after the
    // actual superclass initialization, whether direct or delegated.
    val initTarget = uniqueMethod("application superclass initialization") {
        definingClass == init.definingClass && parameterTypes.isEmpty() && returnType == "V" &&
            implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                (it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE) && ref?.name == "onCreate"
            }
    }
    val initIndex = initTarget.implementation!!.instructions.indexOfFirst {
        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
        (it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE) && ref?.name == "onCreate"
    } + 1

    val track = uniqueMethod("track ID") {
        returnType == "V" && parameterTypes.size == 2 &&
            parameterTypes[0].startsWith("L") && parameterTypes[1] == "Ljava/lang/String;" &&
            AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.FINAL.isSet(accessFlags) &&
            hasString("Null initialPlayabilityStatus")
    }
    val trackInstructions = track.implementation!!.instructions.toList()
    val trackIndex = trackInstructions.indexOfFirst {
        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
        (it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            ref?.returnType == "Ljava/lang/String;" && ref.parameterTypes.isEmpty()
    }
    if (trackIndex < 0 || trackInstructions.getOrNull(trackIndex + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("Track ID fingerprint lacks expected String result")
    }
    val trackRegister = (trackInstructions[trackIndex + 1] as OneRegisterInstruction).registerA

    val progressCaller = uniqueMethod("progress caller") {
        returnType == "V" && hasString("Media progress reported outside media playback: ")
    }
    val progressInstructions = progressCaller.implementation!!.instructions.toList()
    // In 8.40.54 the anchor constructs Laxbu(JJJJJJJZ,String), rather
    // than calling a time callback. Its first long is position in ms.
    val progressIndex = progressInstructions.indices.singleOrNull {
        val ref = (progressInstructions[it] as? ReferenceInstruction)?.reference as? MethodReference
        progressInstructions[it].opcode == Opcode.INVOKE_DIRECT_RANGE && ref?.name == "<init>" &&
            ref.parameterTypes == listOf("J", "J", "J", "J", "J", "J", "J", "Z", "Ljava/lang/String;") &&
            progressInstructions.getOrNull(it + 1)?.opcode == Opcode.IGET_OBJECT
    } ?: throw PatchException("Progress fingerprint must identify exactly one progress constructor")
    val positionRegister = (progressInstructions[progressIndex] as RegisterRangeInstruction).startRegister + 1
    val ratings = listOf("like/like" to 1, "like/dislike" to -1, "like/removelike" to 0).map { (anchor, value) ->
        val constructor = uniqueMethod("rating $anchor") { name == "<init>" && hasString(anchor) }
        val builder = uniqueMethod("rating request builder $anchor") {
            definingClass == constructor.definingClass && name == "a" && parameterTypes.isEmpty() &&
                returnType == "Lbjpu;" && !AccessFlags.STATIC.isSet(accessFlags)
        }
        val instructions = builder.implementation!!.instructions.toList()
        // These APK-specific references are checked before injection. The target
        // is populated before serialization; constructor hooks report no action.
        if (builder.implementation!!.registerCount != 5 || instructions.last().opcode != Opcode.RETURN_OBJECT ||
            (instructions.last() as OneRegisterInstruction).registerA != 0 ||
            instructions.none { (it as? ReferenceInstruction)?.reference.toString() == "Laoql;->a:Lbrjr;" }) {
            throw PatchException("Unexpected rating request layout for $anchor")
        }
        Triple(builder, instructions.lastIndex, value)
    }
    val targetProto = classDefs.singleOrNull { it.type == "Lbrjr;" }
    if (targetProto?.fields?.none { it.name == "c" && it.type == "Ljava/lang/String;" } != false) {
        throw PatchException("Rating video target field is absent")
    }
    initTarget.addInstruction(initIndex, "invoke-static/range {p0 .. p0}, $EXTENSION->init(Landroid/content/Context;)V")
    track.addInstruction(trackIndex + 2, "invoke-static/range {v$trackRegister .. v$trackRegister}, $EXTENSION->onTrack(Ljava/lang/String;)V")
    progressCaller.addInstruction(progressIndex + 1, "invoke-static/range {v$positionRegister .. v${positionRegister + 1}}, $EXTENSION->onPosition(J)V")
    ratings.forEach { (method, index, value) ->
        method.addInstructions(index, "iget-object v1, p0, Laoql;->a:Lbrjr;\niget-object v1, v1, Lbrjr;->c:Ljava/lang/String;\nconst/4 v2, $value\ninvoke-static {v1, v2}, $EXTENSION->onRating(Ljava/lang/String;I)V")
    }
}

private fun Method.hasString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true
private fun BytecodePatchContext.uniqueMethod(label: String, predicate: Method.() -> Boolean): MutableMethod {
    val matches = classDefs.flatMap { it.methods }.filter { it.implementation != null && predicate(it) }
    if (matches.size != 1) throw PatchException("Music telemetry: $label matched ${matches.size} methods; expected exactly one. Unsupported APK or conflicting patches.")
    val original = matches.single()
    return classDefs.getOrReplaceMutable(classDefs[original.definingClass]!!).methods.single {
        it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType
    }
}
