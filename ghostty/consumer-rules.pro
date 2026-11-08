# The JNI library reaches Kotlin only through these two classes, by name:
# JNI_OnLoad registers every native of GhosttyVt and looks up the methods of
# GhosttyVtCallbacks, and the library fails to load when any of them is gone.
# The shrinker cannot see those lookups, so both classes are kept whole.
-keep class io.github.xchacha20_poly1305.libghostty.GhosttyVt { *; }
-keep class io.github.xchacha20_poly1305.libghostty.GhosttyVtCallbacks { *; }
