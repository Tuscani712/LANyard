package io.github.tuscani712.lanyard.core

/**
 * A pure, framework-free LIFO navigation back stack.
 *
 * The stack always holds a root destination that represents the screen the
 * person is on before any sub-screen is opened. [back] refuses to remove that
 * root and returns `null` instead, so the caller knows a Back press on the top
 * screen must fall through to the OS (which exits the app) rather than being
 * swallowed. That is the one guarantee the whole app relies on: while a
 * sub-screen is open Back moves back one screen, and only at the top does it
 * exit.
 *
 * Nothing here touches Android, so the semantics are unit-testable on the JVM.
 */
class NavBackStack<T : Any>(root: T) {
    private val stack = ArrayDeque<T>().apply { addLast(root) }

    /** The destination currently on top. */
    val current: T get() = stack.last()

    /** How many destinations are on the stack, including the root. */
    val depth: Int get() = stack.size

    /** True when [back] would move to a previous screen rather than exit. */
    fun canGoBack(): Boolean = stack.size > 1

    /** Opens [destination], making it the current screen. */
    fun push(destination: T) {
        stack.addLast(destination)
    }

    /**
     * Moves back one screen. Returns the new current destination, or `null` when
     * already at the root, so the caller must let the system handle Back.
     */
    fun back(): T? {
        if (!canGoBack()) return null
        stack.removeLast()
        return stack.last()
    }

    /** Discards every destination above the root and returns to it. */
    fun reset(): T {
        while (stack.size > 1) stack.removeLast()
        return stack.last()
    }

    /** An immutable view of the stack, root first. */
    fun toList(): List<T> = stack.toList()
}
