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
        val method = context.classDefs.getOrReplaceMutable(context.classDefs["Lkoy;"]!!).methods.single { it.name == "j" }
        assertEquals(27, method.implementation!!.registerCount)
        val reference = (method.implementation!!.instructions.first() as ReferenceInstruction).reference as MethodReference
        assertEquals("Ldev/selfhosted/music/NativeQueueCapture;", reference.definingClass)
        assertEquals("capture", reference.name)
        val manager = context.classDefs.getOrReplaceMutable(context.classDefs["Laxvv;"]!!)
        for ((name, anchor) in listOf("x" to "c", "u" to "k")) {
            val instructions = manager.methods.single { it.name == name }.implementation!!.instructions.toList()
            val anchorIndex = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == anchor
            }
            assertTrue(anchorIndex >= 0)
            val callback = (instructions[anchorIndex + 1] as ReferenceInstruction).reference as MethodReference
            assertEquals("Ldev/selfhosted/music/NativeQueueCapture;", callback.definingClass)
            assertEquals("captureManager", callback.name)
        }
        val constructor = manager.methods.single { it.name == "<init>" }.implementation!!.instructions.toList()
        assertEquals("registerManager", ((constructor[constructor.size - 2] as ReferenceInstruction).reference as MethodReference).name)
        val observer = context.classDefs.getOrReplaceMutable(context.classDefs["Laxvw;"]!!)
        for (name in listOf("a", "b", "c")) {
            val callback = (observer.methods.single { it.name == name }.implementation!!.instructions.first() as ReferenceInstruction).reference as MethodReference
            assertEquals("queueChanged", callback.name)
        }
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

    @Test fun `changed replacement anchor rejects before instrumentation`() = fixture(
        alter = { it.replace("Laxvx;->c(Laxvs;)V", "Laxvx;->other(Laxvs;)V") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    @Test fun `changed contents anchor rejects before instrumentation`() = fixture(
        alter = { it.replace("Laxwk;->k(Ljava/util/List;Ljava/util/List;ILaxvt;)V", "Laxwk;->other(Ljava/util/List;Ljava/util/List;ILaxvt;)V") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    @Test fun `changed mutation observer rejects before instrumentation`() = fixture(
        alter = { it.replace("Laxvx;->d()V", "Laxvx;->other()V") },
    ) { context -> assertFailsWith<PatchException> { playbackQueuePatch.execute(context) }; assertNoHook(context) }

    private fun assertNoHook(context: BytecodePatchContext) {
        for (type in listOf("Lkoy;", "Laxvv;", "Laxvw;")) {
            val methods = context.classDefs.getOrReplaceMutable(context.classDefs[type]!!).methods
            assertTrue(methods.flatMap { it.implementation?.instructions.orEmpty() }.none {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == "Ldev/selfhosted/music/NativeQueueCapture;"
            })
        }
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
                .field public f:Laxvs;
                .method public constructor <init>(Laxwf;Lncv;Layfk;)V
                .registers 5
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                new-instance v0, Laxvx;
                invoke-direct {v0}, Laxvx;-><init>()V
                iput-object v0, p0, Laxvv;->e:Laxvx;
                return-void
                .end method
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
                .method public final x(Laxvs;Laxvt;Laxvr;)V
                .registers 5
                iget-object v0, p0, Laxvv;->e:Laxvx;
                iget-object v1, p0, Laxvv;->f:Laxvs;
                invoke-virtual {v0, v1}, Laxvx;->c(Laxvs;)V
                return-void
                .end method
                .method public final u(Ljava/util/List;Ljava/util/List;ILaxvt;)V
                .registers 6
                iget-object v0, p0, Laxvv;->f:Laxvs;
                invoke-interface {v0, p1, p2, p3, p4}, Laxwk;->k(Ljava/util/List;Ljava/util/List;ILaxvt;)V
                return-void
                .end method
            """.trimIndent() + "\n" + method("j()Laxwr;"))
            add("Laxvx;", method("c(Laxvs;)V"))
            add("Laxvw;", """
                .method public final a(III)V
                .registers 4
                invoke-virtual {p0}, Laxvx;->d()V
                return-void
                .end method
                .method public final b(IIII)V
                .registers 5
                invoke-virtual {p0}, Laxvx;->d()V
                return-void
                .end method
                .method public final c(III)V
                .registers 4
                invoke-virtual {p0}, Laxvx;->d()V
                return-void
                .end method
            """.trimIndent())
            add("Laxwk;", method("k(Ljava/util/List;Ljava/util/List;ILaxvt;)V"))
            add("Lchiu;", method("gg()Ljava/lang/Object;"))
            add("Laxwr;", method("t()Ljava/lang/String;") + method("m()Laygw;"))
            add("Laxwv;", method("p()Ljava/lang/Long;"))
            add("Laygw;", method("t()Ljava/lang/String;") + method("u()Ljava/lang/String;") + method("a()I"))
            add("Lnco;", method("h()Ljava/lang/String;") + method("g()Ljava/lang/String;") + method("e()Lbytq;"))
            add("Lncu;", ".implements Laxwr;\n.implements Laxwv;\n")
            add("Lbytq;", ".field public c:Lbjoy;\n")
            add("Lbytp;", ".field public c:Ljava/lang/String;\n.field public d:I\n.field public e:I\n")
            add("Ldev/selfhosted/music/NativeQueueCapture;", ".method public static capture(Ljava/lang/Object;)V\n.registers 1\nreturn-void\n.end method\n.method public static captureManager(Ljava/lang/Object;)V\n.registers 1\nreturn-void\n.end method\n.method public static registerManager(Ljava/lang/Object;)V\n.registers 1\nreturn-void\n.end method\n.method public static queueChanged()V\n.registers 0\nreturn-void\n.end method")
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
