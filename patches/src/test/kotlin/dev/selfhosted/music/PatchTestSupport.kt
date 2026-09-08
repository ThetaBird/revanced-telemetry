package dev.selfhosted.music

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.ResourcePatchContext

/** Runs bytecode-only patch bodies against synthetic fixtures without Android resources. */
@Suppress("UNCHECKED_CAST")
internal fun Patch.execute(context: BytecodePatchContext) {
    if (this === musicTelemetryPatch) {
        context.applyMusicTelemetry()
        return
    }
    val apply = Patch::class.java.getMethod("getApply\$patcher").invoke(this)
        as (BytecodePatchContext, ResourcePatchContext) -> Unit
    val apk = BytecodePatchContext::class.java.getMethod("getApkFile\$patcher").invoke(context) as java.io.File
    val work = BytecodePatchContext::class.java.getMethod("getPatchedFilesPath\$patcher").invoke(context) as java.io.File
    val resources = ResourcePatchContext::class.java.getConstructor(
        java.io.File::class.java, java.io.File::class.java, java.io.File::class.java,
        java.io.File::class.java, String::class.java,
    ).newInstance(apk, work, work, null, null)
    apply(context, resources)
}
