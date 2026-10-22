# AndroidManifest.xml reference

This is the file that makes or breaks a plugin. Get it wrong and Kvaesitso will not list
the plugin at all, with no error message anywhere.

## The shape of a plugin

A plugin is a `<provider>` that answers the intent filter `de.mm20.launcher2.action.PLUGIN`.
One APK can hold several plugins — declare several `<provider>` blocks with different
authorities, and they all show up on the same settings screen because they share the
package metadata.

```xml
<application …>

    <!-- Package-level metadata: shown once for the whole plugin package. -->
    <meta-data android:name="de.mm20.launcher2.plugin.label"       android:value="@string/plugin_package_name" />
    <meta-data android:name="de.mm20.launcher2.plugin.description" android:value="@string/plugin_package_description" />
    <meta-data android:name="de.mm20.launcher2.plugin.author"      android:value="@string/plugin_author" />
    <meta-data android:name="de.mm20.launcher2.plugin.icon"        android:resource="@drawable/ic_plugin" />

    <!-- One plugin. -->
    <provider
        android:name=".MyPluginProvider"
        android:authorities="com.example.mypackage.myplugin"
        android:exported="true"
        android:label="@string/plugin_name">

        <intent-filter>
            <action android:name="de.mm20.launcher2.action.PLUGIN" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>

        <meta-data android:name="de.mm20.launcher2.plugin.description" android:value="@string/plugin_description" />
        <meta-data android:name="de.mm20.launcher2.plugin.icon"        android:resource="@drawable/ic_plugin" />
    </provider>

    <!-- Optional settings screen. -->
    <activity android:name=".SettingsActivity" android:exported="true">
        <intent-filter>
            <action android:name="de.mm20.launcher2.action.PLUGIN_SETTINGS" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>

</application>
```

## Rules that are not optional

**`android:authorities` must be globally unique and permanent.** Two plugins with the same
authority clash, and changing it after release orphans every install. Use your applicationId
plus a suffix:

```
com.example.mypackage.weather
com.example.mypackage.myplugin   (file/place/contact search)
com.example.mypackage.calendar
```

**`android:exported="true"`** is required on the provider. Android refuses to match the
intent filter otherwise.

**The intent filter must be exactly** `de.mm20.launcher2.action.PLUGIN` plus
`android.intent.category.DEFAULT`. That pair is the entire discovery mechanism.

## Metadata keys

| Key                                        | Where          | What it does                                        |
|--------------------------------------------|----------------|-----------------------------------------------------|
| `de.mm20.launcher2.plugin.label`           | `<application>`| Name in the plugin list. Falls back to app label.   |
| `de.mm20.launcher2.plugin.description`     | both           | One line about what the plugin does.               |
| `de.mm20.launcher2.plugin.author`          | `<application>`| Author name.                                        |
| `de.mm20.launcher2.plugin.icon`            | both           | Vector drawable, shown in the list and on badges.   |
| `android:label` (attribute)                | `<provider>`   | Name of this specific plugin instance.              |
| `android:icon` (attribute)                 | `<provider>`   | Alternative to the icon meta-data.                  |

## Permissions

Declare only what you use. A weather or search plugin usually needs nothing beyond:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Talking to another app on the device (a contacts database, a cloud drive app) needs that
app's permission plus a `<queries>` entry, and you request it at runtime from your settings
activity:

```xml
<uses-permission android:name="org.tasks.permission.READ_TASKS" />
<queries>
    <package android:name="org.tasks" />
</queries>
```

## Not declaring a launcher activity

A plugin APK has no home screen. Do not add a `MAIN`/`LAUNCHER` intent filter — it just
gives users an icon that opens an empty screen. The plugin icon in the manifest is used
only for display inside Kvaesitso.

## Debugging the manifest

```bash
# What does the merged manifest actually contain?
./gradlew :app:processDebugManifest
cat app/build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml

# Build and side-load
./gradlew :app:installDebug
adb shell am start -a de.mm20.launcher2.action.PLUGIN
adb logcat -s SlopPlanProvider
```