package android.net

/**
 * Test-only shadow for android.net.Uri.encode.
 *
 * Plain JVM Android unit tests run against non-implemented android.jar stubs,
 * so production calls to Uri.encode throw "Method encode not mocked" unless the
 * method is shadowed on the test classpath. Keep this out of main sources so
 * production encoding semantics remain the real Android implementation.
 */
class Uri private constructor(private val value: String) {
    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun parse(value: String): Uri = Uri(value)

        @JvmStatic
        fun encode(value: String): String = encode(value, null)

        @JvmStatic
        fun encode(value: String, allow: String?): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val char = unsigned.toChar()
                if (char.isAllowed(allow)) {
                    append(char)
                } else {
                    append('%')
                    append(unsigned.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }

        private fun Char.isAllowed(allow: String?): Boolean =
            this in 'A'..'Z' ||
                this in 'a'..'z' ||
                this in '0'..'9' ||
                this == '_' ||
                this == '-' ||
                this == '!' ||
                this == '.' ||
                this == '~' ||
                this == '\'' ||
                this == '(' ||
                this == ')' ||
                this == '*' ||
                allow?.contains(this) == true
    }
}
