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

class MusicActionsPatchTest {
    @Test fun `commands receive zero register callbacks immediately before dispatch`() = fixture { context ->
        musicActionsPatch.execute(context)
        val players = context.classDefs.filter { it.type != "Ldev/selfhosted/music/Telemetry;" }
        players.forEach { player ->
            val instructions = context.classDefs.getOrReplaceMutable(player).methods.single().implementation!!.instructions.toList()
            mapOf("h" to "onInAppSkipNext", "i" to "onInAppSkipPrevious").forEach { (native, callback) ->
                val index = instructions.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == native }
                val before = (instructions[index - 1] as ReferenceInstruction).reference as MethodReference
                assertEquals("Ldev/selfhosted/music/Telemetry;", before.definingClass)
                assertEquals(callback, before.name)
            }
        }
        val output = Files.createTempFile("actions-output", ".dex")
        try { DexPool.writeTo(output.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList().map { context.classDefs.getOrReplaceMutable(it) })) }
        finally { Files.delete(output) }
    }

    @Test fun `missing button anchor rejects before any instrumentation`() = fixture(
        alter = { it.replace("0x7f0b089e", "0x7f0b0000") },
    ) { context -> assertFailsWith<PatchException> { musicActionsPatch.execute(context) }; assertNoHooks(context) }

    @Test fun `ambiguous native dispatch rejects before any instrumentation`() = fixture(
        alter = { it.replace("invoke-interface {p0}, Laxqg;->h()V", "invoke-interface {p0}, Laxqg;->h()V\ninvoke-interface {p0}, Laxqg;->h()V") },
    ) { context -> assertFailsWith<PatchException> { musicActionsPatch.execute(context) }; assertNoHooks(context) }

    private fun assertNoHooks(context: BytecodePatchContext) {
        val calls = context.classDefs.toList().flatMap { context.classDefs.getOrReplaceMutable(it).methods }.flatMap { it.implementation!!.instructions }
        assertTrue(calls.none { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == "Ldev/selfhosted/music/Telemetry;" })
    }

    private fun fixture(alter: (String) -> String = { it }, block: (BytecodePatchContext) -> Unit) {
        val directory = Files.createTempDirectory("actions-patch")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            listOf("Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControls;", "Lmvc;").forEachIndexed { index, type ->
                input.resolve("Player$index.smali").writeText(alter("""
                    .class public $type
                    .super Ljava/lang/Object;
                    .method public onClick(Landroid/view/View;)V
                    .registers 3
                    const v0, 0x7f0b089e
                    const v0, 0x7f0b08a0
                    const v0, 0x8fe9
                    const v0, 0x8fe8
                    invoke-interface {p0}, Laxqg;->h()V
                    invoke-interface {p0}, Laxqg;->i()V
                    return-void
                    .end method
                """.trimIndent()))
            }
            input.resolve("Telemetry.smali").writeText(".class public Ldev/selfhosted/music/Telemetry;\n.super Ljava/lang/Object;\n" +
                listOf("onInAppSkipNext", "onInAppSkipPrevious").joinToString("\n") { ".method public static $it()V\n.registers 0\nreturn-void\n.end method" })
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
