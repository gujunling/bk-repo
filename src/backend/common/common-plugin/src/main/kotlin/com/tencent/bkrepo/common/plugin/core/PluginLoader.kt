/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.common.plugin.core

import org.springframework.core.io.UrlResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.LinkedList
import java.util.jar.JarFile

/**
 * 插件加载器
 */
class PluginLoader(
    private val pluginPath: Path
) {

    val classLoader = PluginClassLoader(pluginPath, javaClass.classLoader)

    init {
        check(Files.exists(pluginPath)) { "Plugin file[$pluginPath] does not exist." }
    }

    fun loadPlugin(): PluginInfo {
        pluginPath.toFile()
        val digest = calculateDigest()
        val metadata = resolveMetadata()
        val extensions = resolveExtensions()
        return PluginInfo(
            id = metadata.id,
            metadata = metadata,
            digest = digest,
            extensionPoints = extensions[ExtensionType.POINT].orEmpty(),
            extensionControllers = extensions[ExtensionType.CONTROLLER].orEmpty()
        )
    }

    private fun resolveExtensions(): HashMap<ExtensionType, LinkedList<String>> {
        val result = HashMap<ExtensionType, LinkedList<String>>()
        try {
            val url = classLoader.getResource(EXTENSION_RESOURCE_LOCATION)
            check(url != null) { "[$EXTENSION_RESOURCE_LOCATION] does not exist in plugin [$pluginPath]" }
            val resource = UrlResource(url)
            val properties = PropertiesLoaderUtils.loadProperties(resource)
            ExtensionType.values().forEach { type ->
                val list = result.getOrPut(type) { LinkedList() }
                properties.getProperty(type.identifier).orEmpty()
                    .split(",")
                    .filter { it.isNotBlank() }
                    .forEach { list.add(it.trim()) }
            }
        } catch (ex: IOException) {
            throw IllegalArgumentException("Unable to load extensions from location [$EXTENSION_RESOURCE_LOCATION]", ex)
        }
        return result
    }

    private fun resolveMetadata(): PluginMetadata {
        try {
            JarFile(pluginPath.toFile()).use { jar ->
                val manifest = jar.manifest
                check(manifest != null) { "[$MANIFEST_LOCATION] does not exist in plugin [$pluginPath]" }
                val attributes = manifest.mainAttributes
                val id = attributes.getValue(PLUGIN_ID).orEmpty()
                //check(id.isNotBlank()) { "Required manifest attribute $PLUGIN_ID is null" }
                val version = attributes.getValue(PLUGIN_VERSION).orEmpty()
                return PluginMetadata(
                    id = id,
                    name = id,
                    version = version
                )
            }
        } catch (ex: IOException) {
            throw IllegalArgumentException("Unable to load manifest from location [$MANIFEST_LOCATION]", ex)
        }
    }

    private fun calculateDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        pluginPath.toFile().inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var sizeRead = input.read(buffer)
            while (sizeRead != -1) {
                digest.update(buffer, 0, sizeRead)
                sizeRead = input.read(buffer)
            }
            return digest.digest().fold("", { str, it -> str + "%02x".format(it) })
        }
    }

    companion object {
        private const val EXTENSION_RESOURCE_LOCATION = "META-INF/artifact.extensions"
        private const val MANIFEST_LOCATION = "META-INF/MANIFEST.MF"
        private const val PLUGIN_ID = "Plugin-Id"
        private const val PLUGIN_VERSION = "Plugin-Version"
        private const val PLUGIN_AUTHOR = "Plugin-Author"
        private const val PLUGIN_DESCRIPTION = "Plugin-Description"
    }
}
