package dev.selfhosted.music

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.nio.file.Files
import kotlin.test.*

class PlaybackQueuePatchTest {
    @Test fun `hook precedes queue update without allocating registers`() = fixture { context ->
        playbackQueuePatch.execute(context)
        val method = context.classDefs.getOrReplaceMutable(context.classDefs["Lkoy;"]!!).methods.single()
        assertEquals(27, method.implementation!!.registerCount)
        val reference = (method.implementation!!.instructions.first() as ReferenceInstruction).reference as MethodReference
        assertEquals("Ldev/selfhosted/music/NativeQueueCapture;", reference.definingClass)
        assertEquals("capture", reference.name)
        val output = Files.createTempFile("queue-output", ".dex")
        try { DexPool.writeTo(output.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList().map { context.classDefs.getOrReplaceMutable(it) })) }
        finally { Files.delete(output) }
    }

    @Test fun `changed provider field rejects before instrumentation`() = fixture(
        alter = { it.replace("b:Lchiu;", "x:Lchiu;") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    @Test fun `missing full list accessor rejects before instrumentation`() = fixture(
        alter = { it.replace("->subList(II)", "->otherList(II)") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    @Test fun `truncated accessor rejects before instrumentation`() = fixture(
        alter = { it.replace("const/4 v2, 0x0", "const/4 v2, 0x1") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    @Test fun `missing metadata callback rejects before instrumentation`() = fixture(
        alter = { it.replace("onPlaybackQueueMetadata", "missingCallback") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    private fun assertNoHook(context: BytecodePatchContext) {
        val method = context.classDefs.getOrReplaceMutable(context.classDefs["Lkoy;"]!!).methods.single()
        assertTrue(method.implementation!!.instructions.none {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == "Ldev/selfhosted/music/NativeQueueCapture;"
        })
    }

    private fun fixture(alter: (String) -> String = { it }, block: (BytecodePatchContext) -> Unit) {
        val directory = Files.createTempDirectory("queue-patch")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            fun add(type: String, body: String) {
                input.resolve(type.substringAfterLast('/').replace(';', '_') + ".smali").writeText(alter(".class public $type\n.super Ljava/lang/Object;\n$body"))
            }
            fun method(signature: String) = ".method public abstract $signature\n.end method\n"
            add("Lkoy;", """
                .field public final b:Lchiu;
                .method public final j()V
                .registers 27
                move-object/from16 v0, p0
                iget-object v1, v0, Lkoy;->b:Lchiu;
                invoke-interface {v1}, Lchiu;->gg()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Laxvv;
                invoke-virtual {v1}, Laxvv;->o()Ljava/util/List;
                return-void
                .end method
            """.trimIndent())
            add("Laxvv;", """
                .field public e:Laxvx;
                .method public final o()Ljava/util/List;
                .registers 4
                iget-object v0, p0, Laxvv;->e:Laxvx;
                invoke-interface {v0}, Lahzj;->size()I
                move-result v1
                const/4 v2, 0x0
                invoke-interface {v0, v2, v1}, Lahzj;->subList(II)Ljava/util/List;
                move-result-object v0
                return-object v0
                .end method
            """.trimIndent() + "\n" + method("j()Laxwr;"))
            add("Lchiu;", method("gg()Ljava/lang/Object;"))
            add("Laxwr;", method("t()Ljava/lang/String;") + method("m()Laygw;"))
            add("Laxwv;", method("p()Ljava/lang/Long;"))
            add("Laygw;", method("t()Ljava/lang/String;") + method("u()Ljava/lang/String;") + method("a()I"))
            add("Lnco;", method("h()Ljava/lang/String;") + method("g()Ljava/lang/String;") + method("e()Lbytq;"))
            add("Lncu;", ".implements Laxwr;\n.implements Laxwv;\n")
            add("Lbytq;", ".field public c:Lbjoy;\n")
            add("Lbytp;", ".field public c:Ljava/lang/String;\n.field public d:I\n.field public e:I\n")
            add("Ldev/selfhosted/music/NativeQueueCapture;", ".method public static capture(Ljava/lang/Object;)V\n.registers 1\nreturn-void\n.end method")
            add("Ldev/selfhosted/music/Telemetry;", ".method public static onPlaybackQueueMetadata(Ljava/lang/String;)V\n.registers 1\nreturn-void\n.end method\n.method public static onPlaylistContext(Ljava/lang/String;Ljava/lang/String;I)V\n.registers 3\nreturn-void\n.end method")
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
