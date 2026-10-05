# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the classes used with @Serializable.
#
# 必须同时覆盖 models 和 data 两个包：原有的规则只写了 models.**，漏掉了
# data/FeedApiClient.kt 里的网络响应模型（FeedItem / FeedDetail / SearchResult 等）。
# debug 包不混淆所以一直没暴露问题，release 开了 R8 后这些类会被削掉序列化器，
# 结果是线上接口全部解析失败 —— 而这类问题只有 release 包才会遇到。
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

-keepclassmembers class tech.iflink.seuwiki.models.** {
    *** Companion;
}
-keepclasseswithmembers class tech.iflink.seuwiki.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class tech.iflink.seuwiki.data.** {
    *** Companion;
}
-keepclasseswithmembers class tech.iflink.seuwiki.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
