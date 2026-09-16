package dev.selfhosted.music

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.nio.file.Files
import kotlin.test.*

class RepeatModePatchTest {
    @Test fun `hooks both repeat controls after command and preserves automatic cycling`() = fixture { context ->
        repeatModePatch.execute(context)
        for (type in listOf("Lkyy;", "Lmur;")) {
            val code = context.classDefs.getOrReplaceMutable(context.classDefs[type]!!).methods.single().implementation!!.instructions.toList()
            val command = code.indexOfFirst { ref(it) == "Lnli;->e()V" }
            assertEquals("Lnli;->a()Lnld;", ref(code[command + 1]))
            assertEquals("Ldev/selfhosted/music/Telemetry;->onRepeatMode(Ljava/lang/Object;)V", ref(code[command + 3]))
            assertEquals(1, code.count { ref(it)?.contains("->onRepeatMode(") == true })
        }
        val cycle = context.classDefs.getOrReplaceMutable(context.classDefs["Lnli;"]!!).methods.single { it.name == "e" }
        assertFalse(cycle.implementation!!.instructions.any { ref(it)?.contains("Telemetry;") == true })
        val output = Files.createTempFile("repeat-output", ".dex")
        try { DexPool.writeTo(output.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList())) }
        finally { Files.delete(output) }
    }
    @Test fun `changed click controller register fails before either mutation`() = fixture(clickRegister = "v6") { context -> rejectUnchanged(context) }
    @Test fun `changed getter fails before mutation`() = fixture(getterField = "b") { context -> rejectUnchanged(context) }
    @Test fun `missing callback fails before mutation`() = fixture(callbackName = "missing") { context -> rejectUnchanged(context) }
    private fun rejectUnchanged(context: BytecodePatchContext) {
        assertFailsWith<PatchException> { repeatModePatch.execute(context) }
        assertFalse(context.classDefs.toList().flatMap { context.classDefs.getOrReplaceMutable(it).methods }
            .flatMap { it.implementation!!.instructions }.any { ref(it)?.contains("->onRepeatMode(") == true })
    }
    private fun ref(instruction: Any) = (instruction as? ReferenceInstruction)?.reference?.toString()
    private fun fixture(clickRegister: String = "p1", getterField: String = "a", callbackName: String = "onRepeatMode",
                        block: (BytecodePatchContext) -> Unit) {
        val directory = Files.createTempDirectory("repeat-patch")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            input.resolve("Controller.smali").writeText("""
                .class public Lnli;
                .super Ljava/lang/Object;
                .method public a()Lnld;
                .registers 2
                iget-object v0, p0, Lnli;->c:Lnlj;
                iget-object v0, v0, Lnlj;->$getterField:Lnld;
                return-object v0
                .end method
                .method public d(Lnld;Z)V
                .registers 6
                iget-object v0, p0, Lnli;->c:Lnlj;
                iget-object v1, v0, Lnlj;->a:Lnld;
                invoke-virtual {v1, p1}, Lnld;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :write
                goto :done
                :write
                iput-object p1, v0, Lnlj;->a:Lnld;
                :done
                return-void
                .end method
                .method public e()V
                .registers 3
                iget-object v0, p0, Lnli;->c:Lnlj;
                iget-object v0, v0, Lnlj;->a:Lnld;
                sget-object v1, Lnld;->d:Lnld;
                invoke-virtual {v0, v1}, Lnld;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :enabled
                return-void
                :enabled
                invoke-virtual {p0}, Lnli;->b()Lnld;
                move-result-object v0
                const/4 v1, 0x1
                invoke-virtual {p0, v0, v1}, Lnli;->d(Lnld;Z)V
                return-void
                .end method
            """.trimIndent())
            input.resolve("Action.smali").writeText("""
                .class public Lkyy;
                .super Ljava/lang/Object;
                .method public i()V
                .registers 2
                iget-object v0, p0, Lkyy;->a:Lchiu;
                invoke-interface {v0}, Lchiu;->gg()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lnli;
                invoke-virtual {v0}, Lnli;->e()V
                return-void
                .end method
            """.trimIndent())
            input.resolve("Click.smali").writeText("""
                .class public Lmur;
                .super Ljava/lang/Object;
                .method public onClick(Landroid/view/View;)V
                .registers 9
                iget-object p1, p0, Lmur;->e:Lnli;
                invoke-virtual {$clickRegister}, Lnli;->e()V
                sget-object v0, Lbqvk;->c:Lbqvk;
                return-void
                .end method
            """.trimIndent())
            input.resolve("Telemetry.smali").writeText("""
                .class public Ldev/selfhosted/music/Telemetry;
                .super Ljava/lang/Object;
                .method public static $callbackName(Ljava/lang/Object;)V
                .registers 1
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
