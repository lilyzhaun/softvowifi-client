package com.vowifi.sw.manager.ui

import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

fun runtimeBlurSupported(): Boolean = runCatching { isRuntimeShaderSupported() }.getOrDefault(false)

fun androidBlurSupported(runtimeShaderSupported: Boolean): Boolean =
    runtimeShaderSupported
