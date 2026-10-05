# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the model classes used with @Serializable.
-keepclassmembers class tech.iflink.seuwiki.models.** {
    *** Companion;
}
-keepclasseswithmembers class tech.iflink.seuwiki.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
