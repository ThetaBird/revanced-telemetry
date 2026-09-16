package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val OPENED_PLAYLIST = "Ldev/selfhosted/music/OpenedPlaylistCapture;"

@Suppress("unused")
val playlistSnapshotPatch = bytecodePatch(
    name = "Opened playlist snapshots",
    description = "Exports opened playlist identity and ordered currently loaded songs, including offscreen rows.",
    use = false,
) {
    compatibleWith("com.google.android.apps.youtube.music"("8.40.54"))
    dependsOn(musicTelemetryPatch)
    apply {
        fun method(type: String, name: String, parameters: List<String>) =
            classDefs[type]?.let { classDefs.getOrReplaceMutable(it) }?.methods?.singleOrNull {
                it.name == name && it.parameterTypes == parameters && it.returnType == "V" && it.implementation != null
            } ?: throw PatchException("Playlist snapshot: missing unique $type->$name")
        val header = method("Logb;", "p", listOf("Ljava/lang/Object;", "Ljava/util/Map;"))
        val bind = method("Lofh;", "a", listOf("Lbccu;", "Lbcbo;", "I"))
        val headerReferences = header.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        val bindReferences = bind.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        if (header.implementation!!.registerCount != 3 ||
            headerReferences.count { it == "Logb;->aI:Ljava/lang/String;" } != 2 ||
            !headerReferences.containsAll(listOf("Lbtjp;->f:Ljava/lang/String;", "Lbtjw;->f:Lbtjv;", "Lbtjv;->b:Ljava/lang/String;")) ||
            bind.implementation!!.registerCount != 9 ||
            !bindReferences.containsAll(listOf("Lofh;->a:Logb;", "Lbcbo;->d(I)Ljava/lang/Object;", "Loij;->a:Lbuew;"))) {
            throw PatchException("Playlist snapshot: changed playlist header or adapter mapping")
        }
        val extension = classDefs[OPENED_PLAYLIST] ?: throw PatchException("Playlist snapshot: missing runtime")
        for (name in listOf("opened", "capture")) {
            if (extension.methods.count { it.name == name && it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
                    it.returnType == "V" && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) } != 1) {
                throw PatchException("Playlist snapshot: missing callback $name")
            }
        }
        header.addInstruction(0, "invoke-static {p0, p1}, $OPENED_PLAYLIST->opened(Ljava/lang/Object;Ljava/lang/Object;)V")
        bind.addInstruction(0, "invoke-static {p0, p2}, $OPENED_PLAYLIST->capture(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
}
