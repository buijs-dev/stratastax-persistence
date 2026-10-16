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
package dev.buijs.stratastax.persistence.reactive.app

/**
 * Computes an entity's very first `version` value on `CREATE`, used by the generated
 * `${Entity}CreateMapper` in place of a hardcoded `1L` - a consumer plugs in a different starting
 * value by registering their own bean of this type (a Quarkus/CDI `@ApplicationScoped
 * InitialVersionStrategy` overrides the library-shipped default - see `quarkus-starter`'s
 * `DefaultInitialVersionStrategy`). Pairs with [VersionIncrementStrategy], which takes over from
 * there on every subsequent update - together they fully describe an `entityVersion()` field's
 * lifecycle, which is why the DSL forbids combining `entityVersion()` with
 * `create(...)`/`update(...)`/ `generatedOnCreateBy...()`/`generatedOnUpdateBy...()`.
 */
fun interface InitialVersionStrategy {

    /** The version value a newly created row should start at. */
    fun initial(): Long
}
