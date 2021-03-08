package io.p8e.crypto

/** String utility functions.  */
object Strings {
    fun toCsv(src: List<String?>?): String? { // return src == null ? null : String.join(", ", src.toArray(new String[0]));
        return join(src, ", ")
    }

    fun join(src: List<String?>?, delimiter: String?): String? {
        return if (src == null) null else java.lang.String.join(delimiter, *src.toTypedArray())
    }

    fun capitaliseFirstLetter(string: String?): String? {
        return if (string == null || string.length == 0) {
            string
        } else {
            string.substring(0, 1).toUpperCase() + string.substring(1)
        }
    }

    fun lowercaseFirstLetter(string: String?): String? {
        return if (string == null || string.length == 0) {
            string
        } else {
            string.substring(0, 1).toLowerCase() + string.substring(1)
        }
    }

    fun zeros(n: Int): String {
        return repeat('0', n)
    }

    fun repeat(value: Char, n: Int): String {
        return String(CharArray(n)).replace("\u0000", value.toString())
    }

    fun isEmpty(s: String?): Boolean {
        return s == null || s.length == 0
    }
}
