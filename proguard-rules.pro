# R8 configuration for 1AI.
#
# We want R8 for exactly one thing: tree shaking. Bouncy Castle ships ~7MB of
# crypto primitives and we reach only a fraction of it, so dead-code removal is
# the difference between a ~3MB APK and a ~9MB one.
#
# Optimisation and obfuscation are deliberately OFF. Neither buys us anything
# here (there is no anti-tamper goal) and both make on-device stack traces
# unreadable, which matters a great deal when the target hardware is a phone
# from 2010 that we cannot attach a debugger to.

-dontobfuscate
-dontoptimize

# Do not warn about optional/absent classes. Bouncy Castle references a number
# of platform classes that exist on the JVM but not on old Android (java.nio.file,
# javax.naming, java.awt, ...). Those code paths are unreachable in our use, and
# R8 removes them; the warnings themselves are noise.
-dontwarn **
-ignorewarnings

# ---------------------------------------------------------------------------
# Framework entry points
#
# Android instantiates these BY NAME from the manifest and from XML, so R8
# cannot see the references and would otherwise strip them.
# ---------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep public class * extends android.widget.BaseAdapter
-keep public class * extends android.widget.ArrayAdapter

# Views referenced from layout XML by class name.
-keep class com.oneai.ui.** { *; }

# ---------------------------------------------------------------------------
# Bouncy Castle
#
# Kept deliberately narrow. We enter Bouncy Castle from exactly one place --
# BcTlsTransportImpl -- and everything reachable from there survives tree
# shaking on its own. Blanket `-keep` rules over org.bouncycastle.crypto.**
# would defeat the point of shrinking at all: bcprov is ~6.6MB of algorithms
# and we use a small fraction of it.
#
# The rules below are the parts that are genuinely loaded by name rather than
# by reference, so R8 cannot see the edge. They were derived by forcing the
# bundled TLS path on in an emulator and watching what broke, not by guessing.
# ---------------------------------------------------------------------------

# The TLS state machine resolves its own negotiated cipher-suite and hash
# implementations through this registry.
-keep class org.bouncycastle.crypto.CryptoServicesRegistrar { *; }
-keep class org.bouncycastle.crypto.CryptoServiceConstraintsException { *; }
-keep class org.bouncycastle.crypto.**$*Constraints { *; }

# Kept because org.bouncycastle.util.Properties is consulted reflectively for
# configuration overrides during static initialisation.
-keep class org.bouncycastle.util.Properties { *; }

# Keep line numbers so on-device stack traces remain usable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Silence notes about missing optional dependencies.
-dontnote **
