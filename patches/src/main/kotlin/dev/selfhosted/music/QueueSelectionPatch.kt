package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*

private const val SELECTION_CAPTURE = "Ldev/selfhosted/music/QueueSelectionCapture;"

@Suppress("unused")
val queueSelectionPatch = bytecodePatch(
    name = "Playback queue selection telemetry",
    description = "Exports accepted native Up Next song clicks on YouTube Music 8.40.54.",
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)
    apply {
        val definitions = classDefs.associateBy { it.type }
        fun requireMethod(type: String, name: String, parameters: List<String>, result: String, static: Boolean = false) =
            definitions[type]?.methods?.singleOrNull {
                it.name == name && it.parameterTypes == parameters && it.returnType == result &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) == static
            } ?: throw PatchException("Queue selection: unsupported method $type->$name")
        fun requireField(type: String, name: String, fieldType: String, public: Boolean) {
            if (definitions[type]?.fields?.count { it.name == name && it.type == fieldType &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) == public && !AccessFlags.STATIC.isSet(it.accessFlags) } != 1)
                throw PatchException("Queue selection: unsupported field $type->$name")
        }
        requireMethod(SELECTION_CAPTURE, "capture", listOf("Ljava/lang/Object;"), "V", true)
        requireMethod("Ldev/selfhosted/music/Telemetry;", "onQueueSongSelected",
            listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "I"), "V", true)
        requireField("Lbccr;", "e", "Lbccp;", false)
        requireField("Lqhr;", "k", "Lncu;", true)
        requireMethod("Laxwr;", "t", emptyList(), "Ljava/lang/String;")
        requireMethod("Laxwr;", "m", emptyList(), "Laygw;")
        requireMethod("Laxwv;", "p", emptyList(), "Ljava/lang/Long;")
        requireMethod("Laygw;", "t", emptyList(), "Ljava/lang/String;")
        requireMethod("Laygw;", "a", emptyList(), "I")
        if (definitions["Lncu;"]?.interfaces?.containsAll(listOf("Laxwr;", "Laxwv;")) != true ||
            definitions["Lqhr;"]?.interfaces?.contains("Lbccp;") != true ||
            definitions["Lbccr;"]?.interfaces?.contains("Landroid/view/View\$OnClickListener;") != true)
            throw PatchException("Queue selection: native row interfaces changed")

        val method = requireMethod("Lbccr;", "onClick", listOf("Landroid/view/View;"), "V")
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val expected = listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
            Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
            Opcode.IPUT_OBJECT, Opcode.IGET_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT,
            Opcode.IGET_OBJECT, Opcode.CONST_STRING, Opcode.INVOKE_VIRTUAL, Opcode.IGET_OBJECT,
            Opcode.INVOKE_VIRTUAL, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE,
            Opcode.RETURN_VOID)
        fun ref(index: Int) = (instructions.getOrNull(index) as? ReferenceInstruction)?.reference?.toString()
        // Pin the complete accepted-click path. The sole rejection branch must bypass the hook.
        if (method.implementation?.registerCount != 6 || instructions.map { it.opcode } != expected ||
            ref(0) != "Lbccr;->e:Lbccp;" ||
            ref(1) != "Lbccp;->fB(Landroid/view/View;)Z" ||
            ref(6) != "Lapzw;->f(Lbmme;)Lbmme;" ||
            ref(9) != "Lbccr;->c:Lancv;" ||
            ref(18) != "Lbccq;->f(Ljava/util/Map;)V" ||
            ref(19) != "Lancv;->c(Lbmme;Ljava/util/Map;)V" ||
            (instructions[0] as TwoRegisterInstruction).let { it.registerA != 0 || it.registerB != 4 } ||
            (instructions[1] as FiveRegisterInstruction).let { it.registerCount != 2 || it.registerC != 0 || it.registerD != 5 } ||
            (instructions[2] as OneRegisterInstruction).registerA != 5 ||
            (instructions[3] as OneRegisterInstruction).registerA != 5 ||
            (instructions[3] as OffsetInstruction).codeOffset != instructions.subList(3, 20).sumOf { it.codeUnits } ||
            (instructions[19] as FiveRegisterInstruction).let { it.registerCount != 3 || it.registerC != 0 || it.registerD != 5 || it.registerE != 1 })
            throw PatchException("Queue selection: accepted row click data flow changed")
        val mutable = classDefs.getOrReplaceMutable(classDefs["Lbccr;"]!!).methods.single {
            it.name == "onClick" && it.parameterTypes == listOf("Landroid/view/View;")
        }
        mutable.addInstruction(19, "invoke-static/range {p0 .. p0}, $SELECTION_CAPTURE->capture(Ljava/lang/Object;)V")
    }
}
