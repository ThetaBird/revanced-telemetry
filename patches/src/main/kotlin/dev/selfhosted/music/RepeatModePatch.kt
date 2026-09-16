package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val REPEAT_TELEMETRY = "Ldev/selfhosted/music/Telemetry;"

/**
 * Native repeat controls in 8.40.54: kyy.i (loop_mode_action) and mur.onClick.
 * Read the selected mode after their synchronous nli.e cycle command. Do not hook
 * nli.e itself: nle also calls it in reaction to player state, without a click.
 * nld names are LOOP_OFF, LOOP_ALL, LOOP_ONE, LOOP_DISABLED; runtime ignores disabled.
 */
@Suppress("unused")
val repeatModePatch = bytecodePatch(
    name = "Repeat mode telemetry",
    description = "Exports song, queue and off repeat modes selected through the native repeat controls.",
    use = false,
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)
    apply {
        fun reject(): Nothing = throw PatchException("Repeat telemetry: changed native repeat control anchors")
        fun method(type: String, name: String, parameters: List<String>, result: String = "V") =
            classDefs[type]?.let { classDefs.getOrReplaceMutable(it) }?.methods?.singleOrNull {
                it.name == name && it.parameterTypes == parameters && it.returnType == result &&
                    !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
            } ?: reject()
        fun reference(instruction: Any) = (instruction as? ReferenceInstruction)?.reference?.toString()
        val getter = method("Lnli;", "a", emptyList(), "Lnld;")
        val reads = getter.implementation!!.instructions.toList()
        if (getter.implementation!!.registerCount != 2 || reads.map { it.opcode } !=
            listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT) ||
            reference(reads[0]) != "Lnli;->c:Lnlj;" || reference(reads[1]) != "Lnlj;->a:Lnld;" ||
            (reads[0] as TwoRegisterInstruction).let { it.registerA != 0 || it.registerB != 1 } ||
            (reads[1] as TwoRegisterInstruction).let { it.registerA != 0 || it.registerB != 0 } ||
            (reads[2] as OneRegisterInstruction).registerA != 0) reject()
        val cycle = method("Lnli;", "e", emptyList()).implementation!!.instructions.toList()
        if (cycle.map { it.opcode } != listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.SGET_OBJECT,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID) ||
            reference(cycle[2]) != "Lnld;->d:Lnld;" || reference(cycle[7]) != "Lnli;->b()Lnld;" ||
            reference(cycle[10]) != "Lnli;->d(Lnld;Z)V") reject()
        val setter = method("Lnli;", "d", listOf("Lnld;", "Z"))
        val writes = setter.implementation!!.instructions.toList()
        if (setter.implementation!!.registerCount != 6 || writes.size < 7 ||
            writes.take(7).map { it.opcode } != listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.GOTO, Opcode.IPUT_OBJECT) ||
            reference(writes[0]) != "Lnli;->c:Lnlj;" || reference(writes[1]) != "Lnlj;->a:Lnld;" ||
            reference(writes[6]) != "Lnlj;->a:Lnld;" ||
            (writes[6] as TwoRegisterInstruction).let { it.registerA != 4 || it.registerB != 0 }) reject()
        val action = method("Lkyy;", "i", emptyList())
        val click = method("Lmur;", "onClick", listOf("Landroid/view/View;"))
        val actionInstructions = action.implementation!!.instructions.toList()
        if (action.implementation!!.registerCount != 2 || actionInstructions.map { it.opcode } !=
            listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID) ||
            reference(actionInstructions[0]) != "Lkyy;->a:Lchiu;" ||
            reference(actionInstructions[1]) != "Lchiu;->gg()Ljava/lang/Object;" ||
            reference(actionInstructions[3]) != "Lnli;" ||
            reference(actionInstructions[4]) != "Lnli;->e()V" ||
            (actionInstructions[4] as FiveRegisterInstruction).let { it.registerCount != 1 || it.registerC != 0 }) reject()
        val clickInstructions = click.implementation!!.instructions.toList()
        if (click.implementation!!.registerCount != 9 || clickInstructions.size < 3 ||
            clickInstructions[0].opcode != Opcode.IGET_OBJECT ||
            reference(clickInstructions[0]) != "Lmur;->e:Lnli;" ||
            (clickInstructions[0] as TwoRegisterInstruction).let { it.registerA != 8 || it.registerB != 7 } ||
            clickInstructions[1].opcode != Opcode.INVOKE_VIRTUAL || reference(clickInstructions[1]) != "Lnli;->e()V" ||
            (clickInstructions[1] as FiveRegisterInstruction).let { it.registerCount != 1 || it.registerC != 8 } ||
            clickInstructions[2].opcode != Opcode.SGET_OBJECT ||
            (clickInstructions[2] as OneRegisterInstruction).registerA != 0 ||
            reference(clickInstructions[2]) != "Lbqvk;->c:Lbqvk;") reject()
        val callback = classDefs[REPEAT_TELEMETRY]?.methods?.count {
            it.name == "onRepeatMode" && it.parameterTypes == listOf("Ljava/lang/Object;") && it.returnType == "V" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        if (callback != 1) throw PatchException("Repeat telemetry: missing onRepeatMode(Object) runtime callback")
        // v0 is dead after the original command at both sites; no register expansion.
        for ((target, index, controller) in listOf(Triple(action, 5, "v0"), Triple(click, 2, "p1"))) {
            target.addInstructions(index, """
                invoke-virtual {$controller}, Lnli;->a()Lnld;
                move-result-object v0
                invoke-static {v0}, $REPEAT_TELEMETRY->onRepeatMode(Ljava/lang/Object;)V
            """.trimIndent())
        }
    }
}
