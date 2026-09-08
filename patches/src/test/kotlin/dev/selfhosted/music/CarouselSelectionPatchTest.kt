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

class CarouselSelectionPatchTest {
    @Test fun `binds selected item identity and observes endpoint at dispatch`() = fixture { context ->
        carouselSelectionPatch.execute(context)
        val bind = context.classDefs.getOrReplaceMutable(context.classDefs["Lqgb;"]!!).methods.single().implementation!!.instructions.toList()
        val index = bind.indexOfFirst { ref(it)?.definingClass == "Lpsu;" }
        assertEquals("bindCarouselItem", ref(bind[index - 1])?.name)
        val overlay = bind.indexOfLast { ref(it)?.definingClass == "Lpsu;" }
        assertTrue(ref(bind[overlay - 1])?.name != "bindCarouselItem", "secondary overlay was instrumented")
        val dispatch = context.classDefs.getOrReplaceMutable(context.classDefs["Lpss;"]!!).methods.single().implementation!!.instructions.toList()
        val target = dispatch.indexOfFirst { ref(it)?.definingClass == "Lancv;" }
        assertEquals("onCarouselDispatch", ref(dispatch[target - 1])?.name)
        val output = Files.createTempFile("carousel-output", ".dex")
        try { DexPool.writeTo(output.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList())) }
        finally { Files.delete(output) }
    }
    @Test fun `changed item register rejects without any mutation`() = fixture("{v6, v3, v8}") { context ->
        assertFailsWith<PatchException> { carouselSelectionPatch.execute(context) }
        assertTrue(context.classDefs.toList().flatMap { context.classDefs.getOrReplaceMutable(it).methods }.flatMap { it.implementation!!.instructions }
            .none { ref(it)?.definingClass == "Ldev/selfhosted/music/Telemetry;" })
    }
    private fun ref(instruction: Any) = (instruction as? ReferenceInstruction)?.reference as? MethodReference
    private fun fixture(viewRegisters: String = "{v5, v3, v8}", block: (BytecodePatchContext) -> Unit) {
        val directory = Files.createTempDirectory("carousel-patch")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            input.resolve("Item.smali").writeText("""
                .class public Lqgb;
                .super Ljava/lang/Object;
                .method public fz(Lbccu;Ljava/lang/Object;)V
                .registers 16
                invoke-static $viewRegisters, Lpsv;->a(Landroid/view/View;[BLapzw;)Lpsu;
                move-result-object v3
                iget-object v12, v2, Lbufj;->h:Lbmme;
                invoke-static {v8, v11, v12, v13}, Lpss;->b(Lancv;Lapzw;Lbmme;Ljava/util/Map;)Lpss;
                move-result-object v11
                invoke-virtual {v3, v11}, Lpsu;->b(Lpst;)V
                invoke-static {v6, v5, v7}, Lpsv;->a(Landroid/view/View;[BLapzw;)Lpsu;
                move-result-object v5
                invoke-static {v8, v7, v4, v9}, Lpss;->b(Lancv;Lapzw;Lbmme;Ljava/util/Map;)Lpss;
                move-result-object v4
                invoke-virtual {v5, v4}, Lpsu;->b(Lpst;)V
                return-void
                .end method
            """.trimIndent())
            input.resolve("Dispatch.smali").writeText("""
                .class public Lpss;
                .super Ljava/lang/Object;
                .method public a()V
                .registers 5
                invoke-interface {v0, v1, v2}, Lancv;->c(Lbmme;Ljava/util/Map;)V
                return-void
                .end method
            """.trimIndent())
            input.resolve("Telemetry.smali").writeText("""
                .class public Ldev/selfhosted/music/Telemetry;
                .super Ljava/lang/Object;
                .method public static bindCarouselItem(Ljava/lang/Object;Landroid/view/View;)V
                .registers 2
                return-void
                .end method
                .method public static onCarouselDispatch(Ljava/lang/Object;Ljava/lang/Object;)V
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
