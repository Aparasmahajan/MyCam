package io.frame.core.data

import java.security.SecureRandom

/** Time-sortable ids (millisecond timestamp + randomness), like a simplified ULID. */
object Ids {
    private val random = SecureRandom()
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    fun newId(nowMillis: Long = System.currentTimeMillis()): String {
        val time = nowMillis.toString(36).padStart(9, '0')
        val rand = buildString { repeat(10) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        return time + rand
    }
}
