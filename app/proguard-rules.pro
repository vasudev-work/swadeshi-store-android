-keep class com.vasudev.swadeshistore.AppItem { *; }
-keep class com.vasudev.swadeshistore.AppRepository { *; }
-keepattributes *Annotation*

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-assumenosideeffects class com.vasudev.swadeshistore.DLog {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
