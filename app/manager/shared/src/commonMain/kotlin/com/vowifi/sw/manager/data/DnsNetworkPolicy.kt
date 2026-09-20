package com.vowifi.sw.manager.data

internal fun orderedWifiFallbackHandles(
    validatedHandles: List<Long>,
    unvalidatedHandles: List<Long>,
): List<Long> = validatedHandles.sorted() + unvalidatedHandles.sorted()
