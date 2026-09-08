package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import java.util.logging.Logger

private const val CALLBACK = "Landroid/media/session/MediaSession\$Callback;"
private const val TELEMETRY = "Ldev/selfhosted/music/Telemetry;"
private val actionCallbacks = linkedMapOf(
    "onSkipToNext" to "onSkipNext",
    "onSkipToPrevious" to "onSkipPrevious",
    "onPlay" to "onPlay",
    "onPause" to "onPause",
)

@Suppress("unused")
val mediaSessionTelemetryPatch = bytecodePatch(
    name = "Media-session action telemetry",
    description = "Optional notification/headset/media-session play, pause, next and previous requests. Does not identify in-app buttons or Quick Play. Logs available action coverage; rejects ambiguous override hierarchies.",
    use = false,
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)

    apply {
        val definitions = classDefs.associateBy { it.type }
        fun ancestors(type: String): List<String> {
            val seen = mutableSetOf<String>()
            val chain = mutableListOf<String>()
            var current = definitions[type]?.superclass
            while (current != null) {
                if (!seen.add(current)) throw PatchException("Media-session telemetry: cyclic class hierarchy at $type")
                chain.add(current)
                if (current == CALLBACK) break
                current = definitions[current]?.superclass
            }
            return chain
        }
        val hierarchies = definitions.keys.associateWith(::ancestors)
        val targets = definitions.values.filter { CALLBACK in hierarchies.getValue(it.type) }.flatMap { type ->
            type.methods.filter { method ->
                method.name in actionCallbacks && method.parameterTypes.isEmpty() && method.returnType == "V" &&
                    AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.implementation != null
            }
        }
        if (targets.isEmpty()) {
            throw PatchException("Media-session telemetry: no concrete public framework MediaSession.Callback action overrides found. This APK is unsupported by the optional patch.")
        }
        // Hooking both an override and its super implementation can double-report one request.
        // Without an APK-backed dispatch contract, reject that shape rather than infer which implementation runs.
        targets.forEach { method ->
            if (targets.any { parent -> parent.name == method.name && parent.definingClass in hierarchies.getValue(method.definingClass) }) {
                throw PatchException("Media-session telemetry: ${method.name} has both ancestor and descendant implementations; cannot guarantee one event per callback. Unsupported override hierarchy.")
            }
        }
        actionCallbacks.values.forEach { callback ->
            val methods = definitions[TELEMETRY]?.methods?.filter {
                it.name == callback && it.parameterTypes.isEmpty() && it.returnType == "V" &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }.orEmpty()
            if (methods.size != 1) throw PatchException("Media-session telemetry: missing extension callback $callback()V")
        }
        targets.forEach { original ->
            val method = classDefs.getOrReplaceMutable(classDefs[original.definingClass]!!).methods.single {
                sameSignature(it, original)
            }
            method.addInstruction(0, "invoke-static {}, $TELEMETRY->${actionCallbacks.getValue(original.name)}()V")
        }
        val coverage = actionCallbacks.keys.joinToString { action ->
            val count = targets.count { it.name == action }
            "$action=${if (count == 0) "unavailable" else "$count override(s)"}"
        }
        Logger.getLogger("MusicTelemetry").info("Media-session telemetry coverage: $coverage. Framework command requests only; in-app buttons and Quick Play origin are not identified.")
    }
}

private fun sameSignature(first: Method, second: Method) =
    first.name == second.name && first.parameterTypes == second.parameterTypes && first.returnType == second.returnType
