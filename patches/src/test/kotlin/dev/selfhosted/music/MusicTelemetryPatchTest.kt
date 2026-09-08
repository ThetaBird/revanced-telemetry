package dev.selfhosted.music

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Synthetic DEX tests prove instrumentation mechanics, not compatibility with a real Music APK. */
class MusicTelemetryPatchTest {
    @Test
    fun `patch emits required callbacks and serializes valid dex with high track register`() = fixture { context, directory ->
        musicTelemetryPatch.execute(context)
        val methods = context.classDefs.toList().flatMap { context.classDefs.getOrReplaceMutable(it).methods }
        val calls = methods.flatMap { method -> method.implementation!!.instructions.mapNotNull { instruction ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == "Ldev/selfhosted/music/Telemetry;") ref.name else null
        } }
        assertEquals(1, calls.count { it == "init" })
        assertEquals(1, calls.count { it == "onTrack" })
        assertEquals(1, calls.count { it == "onPosition" })
        assertEquals(3, calls.count { it == "onRating" })
        assertEquals(0, calls.count { it == "onAction" })
        // Constructing a request is not a rating action. Capture the populated
        // target only when its request body is serialized.
        methods.filter { it.name == "<init>" }.forEach { constructor ->
            assertTrue(constructor.implementation!!.instructions.none {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "onRating"
            })
        }
        methods.filter { it.name == "a" }.forEach { builder ->
            val instructions = builder.implementation!!.instructions.toList()
            val reference = (instructions[instructions.lastIndex - 1] as ReferenceInstruction).reference as MethodReference
            assertEquals(listOf("Ljava/lang/String;", "I"), reference.parameterTypes.map { it.toString() })
            assertTrue(instructions.any { (it as? ReferenceInstruction)?.reference.toString() == "Lbrjr;->c:Ljava/lang/String;" })
        }
        mapOf("like" to 1, "dislike" to -1, "removeLike" to 0).forEach { (name, expected) ->
            val constant = methods.single { it.definingClass == "Lfixture/${name};" && it.name == "a" }.implementation!!.instructions.filterIsInstance<NarrowLiteralInstruction>().last()
            assertEquals(expected, constant.narrowLiteral)
        }
        val positionCall = methods.single { it.name == "report" }.implementation!!.instructions.single { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "onPosition" } as RegisterRangeInstruction
        assertEquals(1, positionCall.startRegister)
        assertEquals(2, positionCall.registerCount)
        val trackCall = methods.single { it.name == "track" }.implementation!!.instructions.single {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "onTrack"
        } as RegisterRangeInstruction
        assertEquals(16, trackCall.startRegister)
        assertEquals(1, trackCall.registerCount)
        val dex = directory.resolve("patched.dex")
        DexPool.writeTo(dex.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList().map { context.classDefs.getOrReplaceMutable(it) }))
        assertEquals(7, DexFileFactory.loadDexFile(dex.toFile(), Opcodes.getDefault()).classes.size)
    }

    @Test
    fun `missing required hook fails before instrumentation`() = fixture(
        ratingSourceTransform = { it.replace("like/dislike", "unsupported/dislike") },
    ) { context, _ ->
        assertTrue(assertFailsWith<PatchException> { musicTelemetryPatch.execute(context) }.message!!.contains("matched 0"))
        val calls = context.classDefs.toList().flatMap { context.classDefs.getOrReplaceMutable(it).methods }.flatMap { it.implementation!!.instructions }
        assertTrue(calls.none { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == "Ldev/selfhosted/music/Telemetry;" })
    }

    @Test
    fun `ambiguous required hook fails closed`() = fixture(
        extraSources = mapOf("Duplicate" to ratingClass("duplicate", "like/like")),
    ) { context, _ ->
        assertTrue(assertFailsWith<PatchException> { musicTelemetryPatch.execute(context) }.message!!.contains("matched 2"))
    }

    @Test
    fun `rating parameter cannot be overwritten as scratch register`() = fixture(
        ratingSourceTransform = { it.replace(".registers 5", ".registers 4") },
    ) { context, _ ->
        assertTrue(assertFailsWith<PatchException> { musicTelemetryPatch.execute(context) }.message!!.contains("request layout"))
    }

    @Test
    fun `initialization follows delegated superclass call`() = fixture(
        source = APP.replace(
            "invoke-super {p0}, Landroid/app/Application;->onCreate()V",
            "invoke-virtual {p0}, Lfixture/App;->initialize()V",
        ) + "\n.method public initialize()V\n.registers 1\ninvoke-super {p0}, Landroid/app/Application;->onCreate()V\nreturn-void\n.end method\n",
    ) { context, _ ->
        musicTelemetryPatch.execute(context)
        val method = context.classDefs.getOrReplaceMutable(context.classDefs["Lfixture/App;"]!!).methods.single { it.name == "initialize" }
        assertEquals("init", ((method.implementation!!.instructions[1] as ReferenceInstruction).reference as MethodReference).name)
    }

    @Test
    fun `collector credentials are not patch options`() {
        assertTrue(musicTelemetryPatch.options.none { it.key == "endpoint" || it.key == "token" })
    }

    @Test
    fun `media action hooks target only proven framework callback overrides`() = fixture(
        source = APP + "\n.method public onPlay()V\n.registers 1\nreturn-void\n.end method\n",
        extraSources = mapOf("Callback" to callbackClass("Lfixture/Callback;", "Landroid/media/session/MediaSession\$Callback;")),
    ) { context, directory ->
        mediaSessionTelemetryPatch.execute(context)
        val callback = context.classDefs.getOrReplaceMutable(context.classDefs["Lfixture/Callback;"]!!)
        mapOf("onSkipToNext" to "onSkipNext", "onSkipToPrevious" to "onSkipPrevious", "onPlay" to "onPlay", "onPause" to "onPause").forEach { (name, hook) ->
            val reference = callback.methods.single { it.name == name }.implementation!!.instructions.first() as ReferenceInstruction
            assertEquals("Ldev/selfhosted/music/Telemetry;->$hook()V", reference.reference.toString())
        }
        val unrelated = context.classDefs.getOrReplaceMutable(context.classDefs["Lfixture/App;"]!!).methods.single { it.name == "onPlay" }
        assertEquals(1, unrelated.implementation!!.instructions.size)
        val dex = directory.resolve("media-patched.dex")
        DexPool.writeTo(dex.toString(), ImmutableDexFile(Opcodes.getDefault(), context.classDefs.toList().map { context.classDefs.getOrReplaceMutable(it) }))
        assertEquals(8, DexFileFactory.loadDexFile(dex.toFile(), Opcodes.getDefault()).classes.size)
    }

    @Test
    fun `media action hooks fail when framework callbacks are absent`() = fixture { context, _ ->
        assertTrue(assertFailsWith<PatchException> { mediaSessionTelemetryPatch.execute(context) }.message!!.contains("no concrete public"))
    }

    @Test
    fun `media action hooks reject ancestor override duplicates before mutation`() = fixture(
        extraSources = mapOf(
            "Parent" to callbackClass("Lfixture/Parent;", "Landroid/media/session/MediaSession\$Callback;"),
            "Child" to callbackClass("Lfixture/Child;", "Lfixture/Parent;"),
        ),
    ) { context, _ ->
        assertTrue(assertFailsWith<PatchException> { mediaSessionTelemetryPatch.execute(context) }.message!!.contains("ancestor and descendant"))
        context.classDefs.filter { it.type == "Lfixture/Parent;" || it.type == "Lfixture/Child;" }.forEach {
            context.classDefs.getOrReplaceMutable(it).methods.forEach { method -> assertEquals(1, method.implementation!!.instructions.size) }
        }
    }

    private fun ratingClass(name: String, anchor: String) = """
        .class public Lfixture/$name;
        .super Laoql;
        .method public constructor <init>()V
            .registers 2
            const-string v0, "$anchor"
            invoke-direct {p0}, Laoql;-><init>()V
            return-void
        .end method
        .method public final a()Lbjpu;
            .registers 5
            iget-object v1, p0, Laoql;->a:Lbrjr;
            const/4 v0, 0x0
            return-object v0
        .end method
    """.trimIndent()

    private fun callbackClass(type: String, parent: String) = buildString {
        appendLine(".class public $type")
        appendLine(".super $parent")
        listOf("onSkipToNext", "onSkipToPrevious", "onPlay", "onPause").forEach {
            appendLine(".method public $it()V")
            appendLine(".registers 1")
            appendLine("return-void")
            appendLine(".end method")
        }
    }

    private fun fixture(source: String = APP, ratingSourceTransform: (String) -> String = { it }, extraSources: Map<String, String> = emptyMap(), block: (BytecodePatchContext, java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("music-patch-fixture")
        try {
            val input = directory.resolve("smali").toFile().apply { mkdirs() }
            input.resolve("App.smali").writeText(source)
            input.resolve("Telemetry.smali").writeText(EXTENSION)
            mapOf("like" to "like/like", "dislike" to "like/dislike", "removeLike" to "like/removelike").forEach { (name, anchor) ->
                input.resolve("$name.smali").writeText(ratingSourceTransform(ratingClass(name, anchor)))
            }
            input.resolve("Target.smali").writeText(".class public Lbrjr;\n.super Ljava/lang/Object;\n.field public c:Ljava/lang/String;\n")
            input.resolve("Request.smali").writeText(".class public Laoql;\n.super Ljava/lang/Object;\n.field public a:Lbrjr;\n")
            extraSources.forEach { (name, body) -> input.resolve("$name.smali").writeText(body) }
            val dex = directory.resolve("classes.dex").toFile()
            assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.path }, input.path))
            // Patcher marks this constructor internal to Kotlin; reflection exercises the real context without a proprietary APK.
            val context = BytecodePatchContext::class.java.getConstructor(java.io.File::class.java, java.io.File::class.java)
                .newInstance(dex, directory.resolve("work").toFile())
            // Patcher normally initializes this lookup cache before executing patches.
            val definitions = context.classDefs.toList()
            context.classDefs.clear()
            context.classDefs.addAll(definitions)
            block(context, directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        private val APP = """
            .class public Lfixture/App;
            .super Landroid/app/Application;
            .field public state:Ljava/lang/Object;
            .method public onCreate()V
                .registers 2
                invoke-super {p0}, Landroid/app/Application;->onCreate()V
                const-string v0, "activity"
                const/4 v0, 0x0
                invoke-virtual {v0}, Landroid/app/ActivityManager;->getRunningAppProcesses()Ljava/util/List;
                return-void
            .end method
            .method public final track(Lfixture/Response;Ljava/lang/String;)V
                .registers 20
                const-string v0, "Null initialPlayabilityStatus"
                invoke-interface/range {p1 .. p1}, Lfixture/Response;->id()Ljava/lang/String;
                move-result-object v16
                return-void
            .end method
            .method public report(J)V
                .registers 17
                const-string v0, "Media progress reported outside media playback: "
                invoke-direct/range {v0 .. v16}, Lfixture/Progress;-><init>(JJJJJJJZLjava/lang/String;)V
                iget-object v0, p0, Lfixture/App;->state:Ljava/lang/Object;
                return-void
            .end method
            .method private time(J)V
                .registers 3
                return-void
            .end method
        """.trimIndent()
        private val EXTENSION = """
            .class public Ldev/selfhosted/music/Telemetry;
            .super Ljava/lang/Object;
        """.trimIndent() + listOf("onSkipNext", "onSkipPrevious", "onPlay", "onPause").joinToString("") {
            "\n.method public static $it()V\n.registers 0\nreturn-void\n.end method\n"
        }
    }
}
