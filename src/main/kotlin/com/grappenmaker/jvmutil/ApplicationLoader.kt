package com.grappenmaker.jvmutil

import java.lang.instrument.Instrumentation

/**
 * Attempts to find the application main [ClassLoader],
 * based on the currently loaded classes from [Instrumentation]
 *
 * Particularly useful in a Minecraft environment, or anything involving custom classloaders
 */
public fun Instrumentation.findMainLoader(mainPackage: String? = null): ClassLoader {
    val appClasses = getAppClasses()

    val targetClass = mainPackage?.let { appClasses.firstOrNull { c -> c.name.startsWith(it) } }
    if (targetClass != null) return targetClass.classLoader

    return appClasses.map { it.classLoader }
        .groupingBy { it }.eachCount()
        .maxByOrNull { (_, count) -> count }?.key
        ?: ClassLoader.getSystemClassLoader()
}