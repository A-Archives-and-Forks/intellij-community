// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplacePutWithAssignment", "ReplaceGetOrSet")

package com.intellij.ide.ui.search

import com.intellij.DynamicBundle
import com.intellij.IntelliJResourceBundle
import com.intellij._doResolveBundle
import com.intellij.ide.plugins.PluginManagerCore.getPluginSet
import com.intellij.ide.ui.search.SearchableOptionsRegistrar.AdditionalLocationProvider
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.ArrayUtil
import com.intellij.util.ResourceUtil
import com.intellij.util.containers.CollectionFactory
import com.intellij.util.lang.UrlClassLoader
import com.intellij.util.xml.dom.XmlElement
import com.intellij.util.xml.dom.readXmlAsModel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.VisibleForTesting
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CancellationException
import java.util.function.Predicate

internal class MySearchableOptionProcessor(private val stopWords: Set<String>) : SearchableOptionProcessor() {
  private val cache = HashSet<String>()
  val storage: MutableMap<CharSequence, LongArray> = CollectionFactory.createCharSequenceMap(20, 0.9f, true)
  @JvmField val identifierTable: IndexedCharsInterner = IndexedCharsInterner()

  override fun addOptions(
    text: String,
    path: String?,
    hit: String?,
    configurableId: String,
    configurableDisplayName: String?,
    applyStemming: Boolean,
  ) {
    cache.clear()
    if (applyStemming) {
      SearchableOptionsRegistrarImpl.collectProcessedWords(text, cache, stopWords)
    }
    else {
      SearchableOptionsRegistrarImpl.collectProcessedWordsWithoutStemming(text, cache, stopWords)
    }
    putOptionWithHelpId(words = cache, id = configurableId, groupName = configurableDisplayName, hit = hit, path = path)
  }

  fun computeHighlightOptionToSynonym(): Map<Pair<String, String>, MutableSet<String>> {
    processSearchableOptions(processor = this)
    return loadSynonyms()
  }

  private fun loadSynonyms(): Map<Pair<String, String>, MutableSet<String>> {
    val result = HashMap<Pair<String, String>, MutableSet<String>>()
    val root = JDOMUtil.load(ResourceUtil.getResourceAsStream(SearchableOptionsRegistrar::class.java.classLoader, "/search/", "synonyms.xml"))
    val cache = HashSet<String>()
    for (configurable in root.getChildren("configurable")) {
      val id = configurable.getAttributeValue("id") ?: continue
      val groupName = configurable.getAttributeValue("configurable_name")
      val synonyms = configurable.getChildren("synonym")
      for (synonymElement in synonyms) {
        val synonym = synonymElement.textNormalize ?: continue
        cache.clear()
        SearchableOptionsRegistrarImpl.collectProcessedWords(synonym, cache, stopWords)
        putOptionWithHelpId(words = cache, id = id, groupName = groupName, hit = synonym, path = null)
      }

      for (optionElement in configurable.getChildren("option")) {
        val option = optionElement.getAttributeValue("name")
        val list = optionElement.getChildren("synonym")
        for (synonymElement in list) {
          val synonym = synonymElement.textNormalize ?: continue
          cache.clear()
          SearchableOptionsRegistrarImpl.collectProcessedWords(synonym, cache, stopWords)
          putOptionWithHelpId(words = cache, id = id, groupName = groupName, hit = synonym, path = null)
          result.computeIfAbsent(Pair(option, id)) { HashSet() }.add(synonym)
        }
      }
    }
    return result
  }

  internal fun putOptionWithHelpId(words: Iterable<String>, id: String, groupName: String?, hit: String?, path: String?) {
    for (word in words) {
      if (stopWords.contains(word)) {
        return
      }

      val stopWord = PorterStemmerUtil.stem(word)
      if (stopWord == null || stopWords.contains(stopWord)) {
        return
      }

      val configs = storage.get(word)
      val packed = SearchableOptionsRegistrarImpl.pack(id, hit, path, groupName, identifierTable)
      if (configs == null) {
        storage.put(word, longArrayOf(packed))
      }
      else if (configs.indexOf(packed) == -1) {
        storage.put(word, ArrayUtil.append(configs, packed))
      }
    }
  }
}

@Serializable
@Internal
data class ConfigurableEntry(
  @JvmField val id: String,
  @JvmField val name: String,
  @JvmField val entries: MutableList<SearchableOptionEntry> = mutableListOf(),
)

@Internal
val INDEX_ENTRY_REGEXP = Regex("""\|b\|([^|]+)\|k\|([^|]+)\|""")

private val LOCATION_EP_NAME = ExtensionPointName<AdditionalLocationProvider>("com.intellij.search.additionalOptionsLocation")

private fun getMessageByCoordinate(s: String, classLoader: ClassLoader, locale: Locale): String {
  val matches = INDEX_ENTRY_REGEXP.findAll(s)
  if (matches.none()) {
    return s
  }

  val result = StringBuilder()
  for (match in matches) {
    val groups = match.groups
    val bundlePath = groups[1]!!.value
    val messageKey = groups[2]!!.value
    val bundle = try {
      _doResolveBundle(loader = classLoader, locale = locale, pathToBundle = bundlePath) as IntelliJResourceBundle
    }
    catch (_: MissingResourceException) {
      continue
    }

    val resolvedMessage = bundle.getMessageOrNull(messageKey) ?: continue
    result.append(resolvedMessage)
  }
  return result.toString()
}

private fun processSearchableOptions(processor: MySearchableOptionProcessor) {
  val visited = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
  val serializer = ConfigurableEntry.serializer()
  for (module in getPluginSet().getEnabledModules()) {
    val classLoader = module.pluginClassLoader
    if (classLoader !is UrlClassLoader || !visited.add(classLoader)) {
      continue
    }

    val classifier = if (module.moduleName == null) "p-${module.pluginId.idString}" else "m-${module.moduleName}"

    val fileName = "$classifier-${SearchableOptionsRegistrar.SEARCHABLE_OPTIONS_XML_NAME}.json"
    val data = classLoader.getResourceAsBytes(fileName, false)
    if (data != null) {
      val locale = DynamicBundle.getLocale()
      try {
        for (item in decodeFromJsonFormat(data, serializer)) {
          for (entry in item.entries) {
            val resolvedHit = getMessageByCoordinate(entry.hit, classLoader, locale).lowercase(locale)
            processor.putOptionWithHelpId(
              words = Iterable {
                SearchableOptionsRegistrarImpl.splitToWordsWithoutStemmingAndStopWords(resolvedHit).iterator()
              },
              id = getMessageByCoordinate(item.id, classLoader, locale),
              groupName = getMessageByCoordinate(item.name, classLoader, locale),
              hit = getMessageByCoordinate(entry.hit, classLoader, locale),
              path = entry.path?.let { getMessageByCoordinate(it, classLoader, locale) },
            )
          }
        }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Throwable) {
        throw RuntimeException("Can't parse searchable options $fileName for plugin ${module.pluginId}", e)
      }
      // if the data is found in JSON format, there's no need to search in XML
      continue
    }

    val xmlName = "${SearchableOptionsRegistrar.SEARCHABLE_OPTIONS_XML_NAME}.xml"
    classLoader.processResources("search", Predicate { it.endsWith(xmlName) }) { _, stream ->
      try {
        readInXml(root = readXmlAsModel(stream), processor)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Throwable) {
        throw RuntimeException("Can't parse searchable options $fileName for plugin ${module.pluginId}", e)
      }
    }
  }

  // process additional locations
  val xmlName = "${SearchableOptionsRegistrar.SEARCHABLE_OPTIONS_XML_NAME}.xml"
  LOCATION_EP_NAME.forEachExtensionSafe { provider ->
    val additionalLocation = provider.additionalLocation ?: return@forEachExtensionSafe
    if (Files.isDirectory(additionalLocation)) {
      Files.list(additionalLocation).use { stream ->
        stream.forEach { file ->
          val fileName = file.fileName.toString()
          try {
            if (fileName.endsWith(xmlName)) {
              readInXml(root = readXmlAsModel(file), processor = processor)
            }
          }
          catch (e: CancellationException) {
            throw e
          }
          catch (e: Throwable) {
            throw RuntimeException("Can't parse searchable options $xmlName", e)
          }
        }
      }
    }
  }
}

@Internal
@VisibleForTesting
@OptIn(ExperimentalSerializationApi::class)
fun decodeFromJsonFormat(data: ByteArray, serializer: KSerializer<ConfigurableEntry>): Sequence<ConfigurableEntry> {
  return Json.decodeToSequence(ByteArrayInputStream(data), serializer, DecodeSequenceMode.WHITESPACE_SEPARATED)
}

private fun readInXml(root: XmlElement, processor: MySearchableOptionProcessor) {
  for (configurable in root.children("configurable")) {
    val id = configurable.getAttributeValue("id") ?: continue
    val name = configurable.getAttributeValue("configurable_name") ?: continue

    for (optionElement in configurable.children("option")) {
      val text = optionElement.getAttributeValue("hit") ?: continue
      processor.putOptionWithHelpId(
        words = listOfNotNull(optionElement.getAttributeValue("name")),
        id = id,
        groupName = name,
        hit = text,
        path = optionElement.getAttributeValue("path"),
      )
    }
  }
}