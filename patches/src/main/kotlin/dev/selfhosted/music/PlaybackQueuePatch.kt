package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val QUEUE_CAPTURE = "Ldev/selfhosted/music/NativeQueueCapture;"

/** Capture the native list before koy.j truncates its media-session presentation to 25 items. */
@Suppress("unused")
val playbackQueuePatch = bytecodePatch(
    name = "Native playback queue telemetry",
    description = "Exports the complete loaded native playback queue and available item metadata on YouTube Music 8.40.54.",
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)
    apply {
        val definitions = classDefs.associateBy { it.type }
        fun requireMethod(type: String, name: String, result: String, parameters: List<String> = emptyList(), static: Boolean = false) =
            definitions[type]?.methods?.singleOrNull {
                it.name == name && it.parameterTypes == parameters && it.returnType == result &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) == static
            } ?: throw PatchException("Playback queue: unsupported 8.40.54 method $type->$name")
        fun requireField(type: String, name: String, fieldType: String) {
            if (definitions[type]?.fields?.count {
                    it.name == name && it.type == fieldType && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                        !AccessFlags.STATIC.isSet(it.accessFlags)
                } != 1) throw PatchException("Playback queue: unsupported field $type->$name:$fieldType")
        }
        requireMethod(QUEUE_CAPTURE, "capture", "V", listOf("Ljava/lang/Object;"), true)
        requireMethod("Ldev/selfhosted/music/Telemetry;", "onPlaybackQueueMetadata", "V", listOf("Ljava/lang/String;"), true)
        requireMethod("Ldev/selfhosted/music/Telemetry;", "onPlaylistContext", "V", listOf("Ljava/lang/String;", "Ljava/lang/String;", "I"), true)
        requireMethod("Laxvv;", "j", "Laxwr;")
        requireMethod("Laxwr;", "m", "Laygw;")
        requireMethod("Laygw;", "t", "Ljava/lang/String;")
        requireMethod("Laygw;", "u", "Ljava/lang/String;")
        requireMethod("Laygw;", "a", "I")
        requireField("Lkoy;", "b", "Lchiu;")
        requireField("Laxvv;", "e", "Laxvx;")
        requireField("Lbytq;", "c", "Lbjoy;")
        requireField("Lbytp;", "c", "Ljava/lang/String;")
        requireField("Lbytp;", "d", "I")
        requireField("Lbytp;", "e", "I")
        requireMethod("Lchiu;", "gg", "Ljava/lang/Object;")
        requireMethod("Laxwr;", "t", "Ljava/lang/String;")
        requireMethod("Laxwv;", "p", "Ljava/lang/Long;")
        requireMethod("Lnco;", "h", "Ljava/lang/String;")
        requireMethod("Lnco;", "g", "Ljava/lang/String;")
        requireMethod("Lnco;", "e", "Lbytq;")
        if (definitions["Lncu;"]?.interfaces?.containsAll(listOf("Laxwr;", "Laxwv;")) != true) {
            throw PatchException("Playback queue: native item interfaces changed")
        }
        val fullQueue = requireMethod("Laxvv;", "o", "Ljava/util/List;")
        val body = fullQueue.implementation?.instructions?.toList().orEmpty()
        // o() must return e.subList(0, e.size()); checking the data flow prevents silently
        // accepting a renamed/truncated accessor with the same reference names.
        val expected = listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT,
            Opcode.CONST_4, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT)
        val fullListFlow = body.map { it.opcode } == expected &&
            (body[0] as TwoRegisterInstruction).let { it.registerA == 0 && it.registerB == 3 } &&
            (body[1] as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == 0 } &&
            (body[2] as OneRegisterInstruction).registerA == 1 &&
            (body[3] as OneRegisterInstruction).registerA == 2 &&
            (body[3] as NarrowLiteralInstruction).narrowLiteral == 0 &&
            (body[4] as FiveRegisterInstruction).let { it.registerCount == 3 && it.registerC == 0 && it.registerD == 2 && it.registerE == 1 } &&
            (body[5] as OneRegisterInstruction).registerA == 0 &&
            (body[6] as OneRegisterInstruction).registerA == 0
        if (!fullListFlow) throw PatchException("Playback queue: full native list accessor data flow changed")
        val references = fullQueue.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()
        if (references.filterIsInstance<FieldReference>().none { it.definingClass == "Laxvv;" && it.name == "e" && it.type == "Laxvx;" } ||
            references.filterIsInstance<MethodReference>().none { it.definingClass == "Lahzj;" && it.name == "size" && it.returnType == "I" } ||
            references.filterIsInstance<MethodReference>().none { it.definingClass == "Lahzj;" && it.name == "subList" && it.parameterTypes == listOf("I", "I") && it.returnType == "Ljava/util/List;" }) {
            throw PatchException("Playback queue: full native list accessor changed")
        }
        val target = requireMethod("Lkoy;", "j", "V")
        val targetReferences = target.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()
        if (targetReferences.filterIsInstance<FieldReference>().none { it.definingClass == "Lkoy;" && it.name == "b" && it.type == "Lchiu;" } ||
            targetReferences.filterIsInstance<MethodReference>().none { it.definingClass == "Laxvv;" && it.name == "o" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" }) {
            throw PatchException("Playback queue: native queue update anchors changed")
        }
        val mutable = classDefs.getOrReplaceMutable(classDefs["Lkoy;"]!!).methods.single {
            it.name == "j" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        mutable.addInstruction(0, "invoke-static/range {p0 .. p0}, $QUEUE_CAPTURE->capture(Ljava/lang/Object;)V")
    }
}
