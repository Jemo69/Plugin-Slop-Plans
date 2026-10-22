package android.net

/**
 * A JVM stand-in for the framework class of the same name.
 *
 * `app/build.gradle.kts` sets `unitTests.isReturnDefaultValues = true`, so on the unit
 * test classpath `android.net.Uri.parse(...)` is a stub that returns `null`. `File` from
 * the plugin SDK declares a non-null `uri: Uri`, so any test that builds one would blow
 * up in `Intrinsics.checkNotNullParameter` rather than assert anything useful.
 *
 * Declaring the class in the test source set puts it ahead of the mockable jar on the
 * classpath, which makes `Uri.parse` behave like the real thing for the parts these
 * tests care about: parsing into an object whose `toString()` is the original string.
 *
 * This is test-only support code. The production build compiles against the real
 * `android.net.Uri` from the SDK and never sees this file.
 */
class Uri private constructor(private val value: String) {

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)
    }
}