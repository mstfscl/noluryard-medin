# minifyEnabled=false oldugu icin bu dosya su an devrede degil.
# Ileride R8 acilirsa AccessibilityService ve Service siniflari manifest'ten
# reflection ile olusturuldugu icin korunmalari gerekir.
-keep class com.noluryard.autoclicker.engine.ClickerAccessibilityService { *; }
-keep class com.noluryard.autoclicker.overlay.OverlayService { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService { *; }
