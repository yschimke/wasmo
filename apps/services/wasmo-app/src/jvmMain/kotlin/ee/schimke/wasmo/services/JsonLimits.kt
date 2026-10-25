package ee.schimke.wasmo.services

internal fun boundedJsonDepth(text: String): Boolean {
  var depth = 0
  var quoted = false
  var escaped = false
  for (char in text) {
    if (quoted) {
      if (escaped) escaped = false
      else if (char == '\\') escaped = true else if (char == '"') quoted = false
    } else {
      when (char) {
        '"' -> quoted = true
        '{',
        '[' -> if (++depth > 64) return false
        '}',
        ']' -> depth--
      }
    }
  }
  return true
}
