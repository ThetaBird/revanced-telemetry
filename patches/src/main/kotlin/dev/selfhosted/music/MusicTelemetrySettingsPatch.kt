package dev.selfhosted.music

import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Document

private const val PREFERENCE = "Landroidx/preference/Preference;"
internal const val TELEMETRY_SETTINGS_KEY = "selfhosted_music_telemetry"
private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

private val musicTelemetrySettingsResourcesPatch = resourcePatch {
    apply {
        document("res/xml/settings_headers.xml").use { addTelemetrySettingsPreference(it) }
    }
}

internal val musicTelemetrySettingsPatch = bytecodePatch {
    dependsOn(musicTelemetrySettingsResourcesPatch)
    apply { applyMusicTelemetrySettings() }
}

internal fun addTelemetrySettingsPreference(document: Document) {
    val root = document.documentElement
    if (root.tagName != "PreferenceScreen") throw PatchException("Unexpected Music settings root")
    val entries = root.getElementsByTagName("*")
    for (index in 0 until entries.length) {
        if ((entries.item(index) as? org.w3c.dom.Element)?.getAttributeNS(ANDROID_NS, "key") == TELEMETRY_SETTINGS_KEY) {
            throw PatchException("Listen telemetry settings entry already exists")
        }
    }
    val row = document.createElement("Preference")
    row.setAttributeNS(ANDROID_NS, "android:key", TELEMETRY_SETTINGS_KEY)
    row.setAttributeNS(ANDROID_NS, "android:title", "Listen telemetry")
    row.setAttributeNS(ANDROID_NS, "android:summary", "Configure your collector URL and token")
    row.setAttributeNS(ANDROID_NS, "android:persistent", "false")
    root.appendChild(row)
}

internal fun BytecodePatchContext.applyMusicTelemetrySettings() {
    // Verified in 8.40.54: H is Preference.performClick; j is its Context and
    // t is android:key (constructor TypedArray index 6). Intercept only after
    // the enabled/selectable guards, preserving every other preference click.
    val definition = classDefs[PREFERENCE] ?: throw PatchException("Music Preference class missing")
    listOf("j" to "Landroid/content/Context;", "t" to "Ljava/lang/String;").forEach { (name, type) ->
        if (definition.fields.none { it.name == name && it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
            throw PatchException("Unexpected Music Preference $name field")
        }
    }
    val original = definition.methods.singleOrNull {
        it.name == "H" && it.parameterTypes.isEmpty() && it.returnType == "V" &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("Music Preference click method missing or ambiguous")
    val instructions = original.implementation?.instructions?.toList() ?: throw PatchException("Music Preference click has no implementation")
    fun reference(index: Int) = (instructions.getOrNull(index) as? ReferenceInstruction)?.reference.toString()
    if (original.implementation!!.registerCount != 3 || instructions.size < 9 ||
        reference(0) != "$PREFERENCE->W()Z" || instructions[1].opcode != Opcode.MOVE_RESULT ||
        instructions[2].opcode != Opcode.IF_EQZ || reference(3) != "$PREFERENCE->x:Z" ||
        instructions[4].opcode != Opcode.IF_NEZ || instructions[5].opcode != Opcode.GOTO ||
        reference(6) != "$PREFERENCE->c()V" || reference(7) != "$PREFERENCE->o:Lejw;" ||
        instructions[8].opcode != Opcode.IF_NEZ ||
        listOf(0, 6).any { (instructions[it] as? FiveRegisterInstruction)?.let { call -> call.registerCount != 1 || call.registerC != 2 } != false } ||
        listOf(1, 2, 4, 8).any { (instructions[it] as? OneRegisterInstruction)?.registerA != 0 } ||
        listOf(3, 7).any { (instructions[it] as? TwoRegisterInstruction)?.let { field -> field.registerA != 0 || field.registerB != 2 } != false }) {
        throw PatchException("Unsupported Music Preference click layout")
    }
    val method = classDefs.getOrReplaceMutable(definition).methods.single {
        it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType
    }
    // Replace the labelled original instruction first: insertion alone would
    // leave the existing selectable branch pointing past the new interceptor.
    method.replaceInstruction(6, "iget-object v0, p0, $PREFERENCE->j:Landroid/content/Context;")
    method.addInstructionsWithLabels(7, """
        iget-object v1, p0, $PREFERENCE->t:Ljava/lang/String;
        invoke-static {v0, v1}, Ldev/selfhosted/music/TelemetrySettings;->onPreferenceClick(Landroid/content/Context;Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :original_click
        return-void
        :original_click
        invoke-virtual {p0}, $PREFERENCE->c()V
    """.trimIndent())
}
