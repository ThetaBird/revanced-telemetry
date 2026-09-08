import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Audit emitted DEX after CLI patching, including the native settings entry hook. */
class AuditApk {
    public static void main(String[] args) throws Exception {
        String extension = "Ldev/selfhosted/music/Telemetry;";
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, Integer> calls = new HashMap<>();
        String settings = "Ldev/selfhosted/music/TelemetrySettings;";
        boolean settingsPresent = false;
        int settingsCalls = 0;
        boolean present = false;
        for (String entry : container.getDexEntryNames()) {
            for (var definition : container.getEntry(entry).getDexFile().getClasses()) {
                if (definition.getType().equals(extension)) present = true;
                if (definition.getType().equals(settings)) settingsPresent = true;
                for (var method : definition.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    for (var instruction : method.getImplementation().getInstructions()) {
                        if (!(instruction instanceof ReferenceInstruction)) continue;
                        var reference = ((ReferenceInstruction) instruction).getReference();
                        if (!definition.getType().startsWith("Ldev/selfhosted/music/")
                                && reference instanceof MethodReference) {
                            var called = (MethodReference) reference;
                            if (called.getDefiningClass().equals(extension))
                                calls.merge(called.getName(), 1, Integer::sum);
                            if (called.getDefiningClass().equals(settings)
                                    && called.getName().equals("onPreferenceClick")
                                    && called.getReturnType().equals("Z")
                                    && called.getParameterTypes().size() == 2
                                    && called.getParameterTypes().get(0).toString().equals("Landroid/content/Context;")
                                    && called.getParameterTypes().get(1).toString().equals("Ljava/lang/String;"))
                                settingsCalls++;
                        }
                    }
                }
            }
        }
        if (!present || !settingsPresent || settingsCalls < 1 || calls.getOrDefault("init", 0) < 1
                || calls.getOrDefault("onTrack", 0) < 1 || calls.getOrDefault("onPosition", 0) < 1
                || calls.getOrDefault("onRating", 0) < 3
                || calls.getOrDefault("onInAppSkipNext", 0) < 2
                || calls.getOrDefault("onInAppSkipPrevious", 0) < 2) {
            throw new IllegalStateException("Required telemetry code or settings hook is absent; do not install this APK.");
        }
        if (java.util.Arrays.asList(args).contains("media")) {
            int count = 0;
            for (String name : new String[]{"onSkipNext", "onSkipPrevious", "onPlay", "onPause"})
                count += calls.getOrDefault(name, 0);
            if (count == 0) throw new IllegalStateException("Requested media-session hooks are absent.");
        }
        if (java.util.Arrays.asList(args).contains("carousel")
                && (calls.getOrDefault("bindCarouselItem", 0) < 1 || calls.getOrDefault("onCarouselDispatch", 0) < 1))
            throw new IllegalStateException("Requested carousel hooks are absent.");
        System.out.println("DEX audit passed: required host-to-telemetry calls and settings entry hook are present.");
    }
}
