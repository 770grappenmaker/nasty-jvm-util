package com.grappenmaker.jvmutil

import java.net.URL
import java.util.*

/**
 * ClassLoader that accepts an ordered list of loaders
 * to which it will delegate calls to. If one loader doesn't return a value,
 * the next one will be requested and so on.
 * Parent of this ClassLoader is determined by [parent]
 */
public class CompoundLoader(
    private val loaders: List<ClassLoader>,
    parent: ClassLoader? = null
) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = loaders.asSequence().mapNotNull { loader ->
        runCatching {
            val type = loader.loadClass(name)
            if (resolve) resolveClass(type)
            type
        }.getOrNull()
    }.firstOrNull() ?: super.loadClass(name, resolve)

    override fun getResource(name: String): URL? =
        loaders.asSequence().mapNotNull { it.getResource(name) }.firstOrNull()

    override fun getResources(name: String): Enumeration<URL> {
        val parentResources = super.getResources(name).toList()
        val all = loaders.fold(parentResources) { acc, curr -> acc + curr.getResources(name).toList() }
        return Collections.enumeration(all)
    }
}