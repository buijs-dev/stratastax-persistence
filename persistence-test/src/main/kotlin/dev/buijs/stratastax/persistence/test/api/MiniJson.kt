/**
 * Copyright (c) 2021 - 2026 Buijs Software
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.buijs.stratastax.persistence.test.api

/**
 * A minimal recursive-descent JSON reader - just enough to parse Postgres's own `EXPLAIN (FORMAT
 * JSON)` output (objects, arrays, strings, numbers, booleans, null) without pulling in a
 * general-purpose JSON library for one narrow, well-known input shape. Not a general-purpose parser
 * and not meant to become one - if a future need calls for real JSON writing/round-tripping, reach
 * for a real library instead of growing this.
 */
internal object MiniJson {

    fun parse(text: String): Any? =
        Parser(text).let { it.parseValue().also { _ -> it.skipWhitespace() } }

    private class Parser(
        private val text: String,
    ) {

        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (val c = text[index]) {
                '{' -> {
                    parseObject()
                }
                '[' -> {
                    parseArray()
                }
                '"' -> {
                    parseString()
                }
                't',
                'f',
                -> {
                    parseBoolean()
                }
                'n' -> {
                    parseNull()
                }
                else -> {
                    if (c == '-' || c.isDigit()) {
                        parseNumber()
                    } else {
                        error("Unexpected character '$c' at index $index")
                    }
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }

            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                    }
                    '}' -> {
                        index++
                        return result
                    }
                    else -> {
                        error("Expected ',' or '}' at index $index")
                    }
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }

            while (true) {
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                    }
                    ']' -> {
                        index++
                        return result
                    }
                    else -> {
                        error("Expected ',' or ']' at index $index")
                    }
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = text[index++]) {
                    '"' -> {
                        return sb.toString()
                    }
                    '\\' -> {
                        when (val escaped = text[index++]) {
                            '"' -> {
                                sb.append('"')
                            }
                            '\\' -> {
                                sb.append('\\')
                            }
                            '/' -> {
                                sb.append('/')
                            }
                            'b' -> {
                                sb.append('\b')
                            }
                            'f' -> {
                                sb.append('\u000C')
                            }
                            'n' -> {
                                sb.append('\n')
                            }
                            'r' -> {
                                sb.append('\r')
                            }
                            't' -> {
                                sb.append('\t')
                            }
                            'u' -> {
                                val hex = text.substring(index, index + 4)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> {
                                error("Unknown escape '\\$escaped' at index $index")
                            }
                        }
                    }
                    else -> {
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) {
                index++
            }

            return text.substring(start, index).toDouble()
        }

        private fun parseBoolean(): Boolean =
            if (text.startsWith("true", index)) {
                index += 4
                true
            } else if (text.startsWith("false", index)) {
                index += 5
                false
            } else {
                error("Expected boolean at index $index")
            }

        private fun parseNull(): Any? {
            check(text.startsWith("null", index)) { "Expected null at index $index" }

            index += 4
            return null
        }

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = text[index]

        private fun expect(c: Char) {
            check(text[index] == c) { "Expected '$c' at index $index but found '${text[index]}'" }

            index++
        }
    }
}
