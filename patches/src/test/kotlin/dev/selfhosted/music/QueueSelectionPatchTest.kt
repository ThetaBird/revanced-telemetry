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

class QueueSelectionPatchTest {
    @Test fun `only accepted dispatch gets callback and dex remains serializable`() = fixture { context ->
        queueSelectionPatch.execute(context)
        val method = context.classDefs.getOrReplaceMutable(context.classDefs["Lbccr;"]!!).methods.single()
        val instructions = method.implementation!!.instructions.toList()
        assertEquals(6, method.implementation!!.registerCount)
        assertEquals("capture", ((instructions[19] as ReferenceInstruction).reference as MethodReference).name)
        assertEquals("c", ((instructions[20] as ReferenceInstruction).reference as MethodReference).name)
        val branch = instructions[3] as com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
        assertEquals(instructions.subList(3, 21).sumOf { it.codeUnits }, branch.codeOffset,
            "blocked click must jump to return past telemetry")
        val output = Files.createTempFile("selection-output", ".dex")
        try { DexPool.writeTo(output.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList().map { context.classDefs.getOrReplaceMutable(it) })) }
        finally { Files.delete(output) }
    }
    @Test fun `inverted click guard rejects`() = rejection { it.replace("if-nez p1", "if-eqz p1") }
    @Test fun `wrong guard register rejects`() = rejection { it.replace("if-nez p1", "if-nez v0") }
    @Test fun `wrong dispatch rejects`() = rejection { it.replace("Lancv;->c(", "Lancv;->d(") }
    @Test fun `missing row identity rejects`() = rejection { it.replace("k:Lncu;", "x:Lncu;") }
    @Test fun `missing callback rejects`() = rejection { it.replace("onQueueSongSelected", "absentCallback") }
    @Test fun `rejection branch entering dispatch rejects`() = rejection {
        it.replace("invoke-interface {v0, p1, v1}, Lancv;", ":dispatch\ninvoke-interface {v0, p1, v1}, Lancv;")
            .replace("if-nez p1, :cond_2d", "if-nez p1, :dispatch")
    }
    private fun rejection(alter: (String) -> String) = fixture(alter) { context ->
        assertFailsWith<PatchException> { queueSelectionPatch.execute(context) }
        val body = context.classDefs.getOrReplaceMutable(context.classDefs["Lbccr;"]!!).methods.single().implementation!!.instructions
        assertTrue(body.none { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "capture" })
    }
    private fun fixture(alter: (String) -> String = { it }, block: (BytecodePatchContext) -> Unit) {
        val directory = Files.createTempDirectory("queue-patch")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            fun add(type: String, body: String) {
                input.resolve(type.substringAfterLast('/').replace(';', '_') + ".smali").writeText(alter(".class public $type\n.super Ljava/lang/Object;\n$body"))
            }
            fun method(signature: String) = ".method public abstract $signature\n.end method\n"
            add("Lbccr;", """
                .implements Landroid/view/View${'$'}OnClickListener;
                .field private final e:Lbccp;
.method public onClick(Landroid/view/View;)V
    .registers 6
    iget-object v0, p0, Lbccr;->e:Lbccp;
    invoke-interface {v0, p1}, Lbccp;->fB(Landroid/view/View;)Z
    move-result p1
    if-nez p1, :cond_2d
    iget-object p1, p0, Lbccr;->f:Lapzw;
    iget-object v0, p0, Lbccr;->g:Lbmme;
    invoke-interface {p1, v0}, Lapzw;->f(Lbmme;)Lbmme;
    move-result-object p1
    iput-object p1, p0, Lbccr;->g:Lbmme;
    iget-object v0, p0, Lbccr;->c:Lancv;
    new-instance v1, Ljava/util/HashMap;
    invoke-direct {v1}, Ljava/util/HashMap;-><init>()V
    iget-object v2, p0, Lbccr;->f:Lapzw;
    const-string v3, "com.google.android.libraries.youtube.logging.interaction_logger"
    invoke-virtual {v1, v3, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    iget-object v2, p0, Lbccr;->h:Ljava/util/Map;
    invoke-virtual {v1, v2}, Ljava/util/HashMap;->putAll(Ljava/util/Map;)V
    iget-object v2, p0, Lbccr;->i:Lbccq;
    invoke-interface {v2, v1}, Lbccq;->f(Ljava/util/Map;)V
    invoke-interface {v0, p1, v1}, Lancv;->c(Lbmme;Ljava/util/Map;)V
    :cond_2d
    return-void
.end method
            """.trimIndent())
            add("Lqhr;", ".implements Lbccp;\n.field public k:Lncu;\n")
            add("Lncu;", ".implements Laxwr;\n.implements Laxwv;\n")
            add("Laxwr;", method("t()Ljava/lang/String;") + method("m()Laygw;"))
            add("Laxwv;", method("p()Ljava/lang/Long;"))
            add("Laygw;", method("t()Ljava/lang/String;") + method("a()I"))
            add("Ldev/selfhosted/music/QueueSelectionCapture;", ".method public static capture(Ljava/lang/Object;)V\n.registers 1\nreturn-void\n.end method")
            add("Ldev/selfhosted/music/Telemetry;", ".method public static onQueueSongSelected(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V\n.registers 4\nreturn-void\n.end method")
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
