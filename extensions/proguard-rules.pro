-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.revanced.** {
  *;
}
-keep class com.google.** {
  *;
}

# Extension entry points are invoked from bytecode the patcher injects after R8 has run, and each
# module is shrunk on its own, so R8 cannot see those references: every class the bundle or another
# module uses at runtime must be kept explicitly.
#
# Plain `-keep class app.morphe.**` would do that, but it also keeps the settings screens that
# piko-extension-library carries and this bundle pulls in for the shared bottom sheet and never opens. So the
# packages that must stay are listed here, and piko-patches-library keeps its own widgets, theme
# and logging through the `consumer-rules.pro` it ships.
#
# A missing keep does NOT fail the build or the patch: it only surfaces on device, for example as
# an InstantiationError from a class R8 emptied. When Instagram starts importing another class from
# a shared module, add it here.
-keep class app.morphe.extension.* { *; }
-keep class app.morphe.extension.instagram.** { *; }
-keep class app.morphe.extension.shared.** { *; }
-keep class app.morphe.extension.library.** { *; }
-keep class app.morphe.extension.crimera.* { *; }
-keep class app.morphe.extension.crimera.constants.** { *; }
-keep class app.morphe.extension.crimera.downloader.** { *; }
-keep class app.morphe.extension.crimera.sharedPreference.** { *; }
# The setting value types Instagram's `Settings` instantiates. Listed by class, not by package:
# `crimera.settings` also holds the library's settings screens, which must stay out of the app.
# The shared module is shrunk on its own, so nothing in it references these; without the rule R8
# empties them and `Settings.<clinit>` dies with an InstantiationError at runtime.
-keep class app.morphe.extension.crimera.settings.Setting { *; }
-keep class app.morphe.extension.crimera.settings.BooleanSetting { *; }
-keep class app.morphe.extension.crimera.settings.StringSetting { *; }
