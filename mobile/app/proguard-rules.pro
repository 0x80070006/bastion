# Protobuf lite: generated message classes are accessed reflectively by the runtime.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# libsodium through JNA: native bindings are resolved by name at runtime.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**
