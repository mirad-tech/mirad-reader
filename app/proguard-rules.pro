# Add project specific ProGuard rules here.
-keep class com.mirad.reader.core.** { *; }

# Context 通过类名反射创建解释器；仅保留此入口，其余实现仍可由 R8 优化。
-keep class org.mozilla.javascript.Interpreter { public <init>(); }

# Rhino 的可选 JavaBean -> JSON 转换依赖桌面 JDK 的 java.beans。
# 书源只注入 JS 原生值，WrapFactory 禁止 Java 对象；不会调用 BEAN 转换器。
# 仅忽略这个不可达桌面分支引用的五个 Android 不存在类型。
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
