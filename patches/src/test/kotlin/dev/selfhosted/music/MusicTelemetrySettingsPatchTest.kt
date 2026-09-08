package dev.selfhosted.music

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class MusicTelemetrySettingsPatchTest {
    @Test fun `settings row preserves existing entries and rejects duplicate`() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse("""<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"><Preference android:key="existing"/></PreferenceScreen>""".byteInputStream())
        addTelemetrySettingsPreference(document)
        val preferences = document.getElementsByTagName("Preference")
        assertEquals(2, preferences.length)
        assertEquals("existing", (preferences.item(0) as org.w3c.dom.Element).getAttributeNS("http://schemas.android.com/apk/res/android", "key"))
        assertEquals(TELEMETRY_SETTINGS_KEY, (preferences.item(1) as org.w3c.dom.Element).getAttributeNS("http://schemas.android.com/apk/res/android", "key"))
        assertFailsWith<PatchException> { addTelemetrySettingsPreference(document) }
    }

    @Test fun `settings interception follows guards and preserves original click branch`() = fixture(SOURCE) { context, directory ->
        context.applyMusicTelemetrySettings()
        val method = context.classDefs.getOrReplaceMutable(context.classDefs["Landroidx/preference/Preference;"]!!).methods.single()
        val instructions = method.implementation!!.instructions
        assertEquals(3, method.implementation!!.registerCount)
        assertEquals(Opcode.IGET_OBJECT, instructions[6].opcode)
        // The original selectable branch must enter our interceptor, not skip it.
        assertEquals(6, (instructions[4] as BuilderOffsetInstruction).target.location.index)
        assertEquals(12, (instructions[10] as BuilderOffsetInstruction).target.location.index)
        assertEquals("Landroidx/preference/Preference;->c()V", (instructions[12] as ReferenceInstruction).reference.toString())
        assertEquals("Ldev/selfhosted/music/TelemetrySettings;->onPreferenceClick(Landroid/content/Context;Ljava/lang/String;)Z", (instructions[8] as ReferenceInstruction).reference.toString())
        val dex = directory.resolve("patched.dex").toFile()
        DexPool.writeTo(dex.path, ImmutableDexFile(Opcodes.getDefault(), listOf(context.classDefs.getOrReplaceMutable(context.classDefs["Landroidx/preference/Preference;"]!!))))
        assertEquals(1, DexFileFactory.loadDexFile(dex, Opcodes.getDefault()).classes.size)
    }

    @Test fun `changed preference layout fails before mutation`() = fixture(SOURCE.replace(".registers 3", ".registers 4")) { context, _ ->
        assertFailsWith<PatchException> { context.applyMusicTelemetrySettings() }
        val instructions = context.classDefs.getOrReplaceMutable(context.classDefs["Landroidx/preference/Preference;"]!!).methods.single().implementation!!.instructions
        assertTrue(instructions.none { (it as? ReferenceInstruction)?.reference.toString().contains("TelemetrySettings") })
    }

    private fun fixture(source: String, body: (BytecodePatchContext, java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("music-settings-fixture")
        try {
            val sourceFile = directory.resolve("Preference.smali").toFile().apply { writeText(source) }
            val dex = directory.resolve("classes.dex").toFile()
            assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.path }, sourceFile.path))
            val context = BytecodePatchContext::class.java.getConstructor(java.io.File::class.java, java.io.File::class.java).newInstance(dex, directory.resolve("work").toFile())
            val definitions = context.classDefs.toList()
            context.classDefs.clear()
            context.classDefs.addAll(definitions)
            body(context, directory)
        } finally { directory.toFile().deleteRecursively() }
    }

    companion object {
        private val SOURCE = """
            .class public Landroidx/preference/Preference;
            .super Ljava/lang/Object;
            .field public final j:Landroid/content/Context;
            .field public t:Ljava/lang/String;
            .method public final H()V
                .registers 3
                invoke-virtual {p0}, Landroidx/preference/Preference;->W()Z
                move-result v0
                if-eqz v0, :end
                iget-boolean v0, p0, Landroidx/preference/Preference;->x:Z
                if-nez v0, :click
                goto :end
                :click
                invoke-virtual {p0}, Landroidx/preference/Preference;->c()V
                iget-object v0, p0, Landroidx/preference/Preference;->o:Lejw;
                if-nez v0, :end
                :end
                return-void
            .end method
        """.trimIndent()
    }
}
