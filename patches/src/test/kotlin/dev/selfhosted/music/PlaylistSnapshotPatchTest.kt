package dev.selfhosted.music

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.nio.file.Files
import kotlin.test.*

class PlaylistSnapshotPatchTest {
    @Test fun `captures header and full adapter before host argument reuse`() = fixture { context ->
        playlistSnapshotPatch.execute(context)
        for ((type, name) in listOf("Logb;" to "opened", "Lofh;" to "capture")) {
            val method = context.classDefs.getOrReplaceMutable(context.classDefs[type]!!).methods.single()
            assertEquals(name, ref(method.implementation!!.instructions.first())?.name)
        }
        val output = Files.createTempFile("playlist-output", ".dex")
        try { DexPool.writeTo(output.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList())) }
        finally { Files.delete(output) }
    }
    @Test fun `changed adapter mapping rejects before mutation`() = fixture("Lother;->a:Lbuew;") { context ->
        assertFailsWith<PatchException> { playlistSnapshotPatch.execute(context) }
        assertTrue(context.classDefs.toList().flatMap { context.classDefs.getOrReplaceMutable(it).methods }
            .flatMap { it.implementation!!.instructions }.none { ref(it)?.name == "opened" })
    }
    private fun ref(instruction: Any) = (instruction as? ReferenceInstruction)?.reference as? MethodReference
    private fun fixture(wrapper: String = "Loij;->a:Lbuew;", block: (BytecodePatchContext) -> Unit) {
        val directory = Files.createTempDirectory("playlist-patch")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            input.resolve("Header.smali").writeText("""
                .class public Logb;
                .super Ljava/lang/Object;
                .method protected p(Ljava/lang/Object;Ljava/util/Map;)V
                .registers 3
                iget-object p2, p1, Lbtjp;->f:Ljava/lang/String;
                iput-object p2, p0, Logb;->aI:Ljava/lang/String;
                iget-object p1, p1, Lbtjw;->f:Lbtjv;
                iget-object p1, p1, Lbtjv;->b:Ljava/lang/String;
                iput-object p1, p0, Logb;->aI:Ljava/lang/String;
                return-void
                .end method
            """.trimIndent())
            input.resolve("Bind.smali").writeText("""
                .class public Lofh;
                .super Ljava/lang/Object;
                .method public a(Lbccu;Lbcbo;I)V
                .registers 9
                invoke-interface {p2, p3}, Lbcbo;->d(I)Ljava/lang/Object;
                move-result-object v0
                iget-object v2, p0, Lofh;->a:Logb;
                iget-object v0, v0, $wrapper
                return-void
                .end method
            """.trimIndent())
            input.resolve("Capture.smali").writeText("""
                .class public Ldev/selfhosted/music/OpenedPlaylistCapture;
                .super Ljava/lang/Object;
                .method public static opened(Ljava/lang/Object;Ljava/lang/Object;)V
                .registers 2
                return-void
                .end method
                .method public static capture(Ljava/lang/Object;Ljava/lang/Object;)V
                .registers 2
                return-void
                .end method
            """.trimIndent())
            val dex = directory.resolve("classes.dex").toFile()
            assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.path }, input.path))
            val context = BytecodePatchContext::class.java.getConstructor(java.io.File::class.java, java.io.File::class.java)
                .newInstance(dex, directory.resolve("work").toFile())
            val definitions = context.classDefs.toList()
            context.classDefs.clear()
            context.classDefs.addAll(definitions)
            block(context)
        } finally { directory.toFile().deleteRecursively() }
    }
}
