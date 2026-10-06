# Protobuf lite: generated message classes are accessed reflectively by the runtime.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
