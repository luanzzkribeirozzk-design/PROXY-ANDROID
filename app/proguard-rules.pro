# Ofuscação máxima
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''
-hierarchyview

# Manter classes básicas do Android
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Ofuscar nomes de métodos e campos
-useuniqueclassmembernames
-dontusemixedcaseclassnames
-keepattributes Signature,InnerClasses,EnclosingMethod,AnnotationDefault

# Remover logs para segurança
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
