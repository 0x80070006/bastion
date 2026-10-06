package org.bastion.core.domain.model

/**
 * Explicit result type used across layers. Errors are typed values, never exceptions
 * (docs/ARCHITECTURE.md §7).
 */
public sealed interface Outcome<out T, out E> {
    public data class Success<out T>(val value: T) : Outcome<T, Nothing>

    public data class Failure<out E>(val error: E) : Outcome<Nothing, E>
}

public inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

public fun <T, E> Outcome<T, E>.getOrNull(): T? = (this as? Outcome.Success)?.value
