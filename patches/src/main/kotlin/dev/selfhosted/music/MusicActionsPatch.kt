package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ACTION_TELEMETRY = "Ldev/selfhosted/music/Telemetry;"

/**
 * APK-backed 8.40.54 anchors. Both player layouts bind resource 0x7f0b089e
 * (player_control_next_button) and 0x7f0b08a0 (player_control_previous_button)
 * to themselves as OnClickListener. Their onClick branches dispatch h()/i()
 * respectively, after checking whether the command is allowed. Instrument the
 * dispatch sites, not the shared player implementation used by automatic advance.
 */
@Suppress("unused")
val musicActionsPatch = bytecodePatch(
    name = "In-app Music player action telemetry",
    description = "Exports accepted next/previous button commands from both 8.40.54 player layouts. Does not identify Quick Play selections.",
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)

    apply {
        val callbacks = mapOf("h" to "onInAppSkipNext", "i" to "onInAppSkipPrevious")
        val definitions = classDefs.associateBy { it.type }
        callbacks.values.forEach { callback ->
            val matches = definitions[ACTION_TELEMETRY]?.methods?.count {
                it.name == callback && it.parameterTypes.isEmpty() && it.returnType == "V" &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: 0
            if (matches != 1) throw PatchException("Music actions: missing extension callback $callback()V")
        }
        // Resolve and validate all targets before changing any instructions.
        val targets = listOf(
            "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControls;",
            "Lmvc;",
        ).map { type ->
            val definition = definitions[type] ?: throw PatchException("Music actions: missing 8.40.54 player $type")
            val resourceIds = definition.methods.flatMap { method ->
                method.implementation?.instructions?.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.orEmpty()
            }.toSet()
            if (!resourceIds.containsAll(listOf(0x7f0b089e, 0x7f0b08a0))) {
                throw PatchException("Music actions: next/previous resource anchors missing in $type")
            }
            val method = definition.methods.singleOrNull {
                it.name == "onClick" && it.parameterTypes == listOf("Landroid/view/View;") &&
                    it.returnType == "V" && it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Music actions: missing unique player onClick in $type")
            val instructions = method.implementation!!.instructions.toList()
            val analytics = instructions.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.toSet()
            if (!analytics.containsAll(listOf(0x8fe9, 0x8fe8))) {
                throw PatchException("Music actions: next/previous click analytics anchors missing in $type")
            }
            val sites = callbacks.map { (command, callback) ->
                val matches = instructions.indices.filter { index ->
                    val instruction = instructions[index]
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    instruction.opcode == Opcode.INVOKE_INTERFACE && reference?.definingClass == "Laxqg;" &&
                        reference.name == command && reference.parameterTypes.isEmpty() && reference.returnType == "V"
                }
                if (matches.size != 1) throw PatchException("Music actions: $type $command dispatch matched ${matches.size} sites")
                matches.single() to callback
            }
            val mutable = classDefs.getOrReplaceMutable(classDefs[type]!!).methods.single {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            mutable to sites
        }
        targets.forEach { (method, sites) ->
            sites.sortedByDescending { it.first }.forEach { (index, callback) ->
                method.addInstruction(index, "invoke-static {}, $ACTION_TELEMETRY->$callback()V")
            }
        }
    }
}
