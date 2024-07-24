// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.featureStatistics.fusCollectors.FileEditorCollector.MarkupGraveEvent
import com.intellij.featureStatistics.fusCollectors.FileEditorCollector.logEditorMarkupGrave
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.stickyLines.StickyLinesModelImpl
import com.intellij.openapi.editor.impl.zombie.*
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.MarkupEditorFilter
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.util.CommonProcessors
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.containers.ConcurrentIntObjectMap
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.TestOnly
import javax.swing.Icon

@Internal
open class HighlightingNecromancerAwaker : NecromancerAwaker<HighlightingZombie> {
  override fun awake(project: Project, coroutineScope: CoroutineScope): Necromancer<HighlightingZombie> {
    return HighlightingNecromancer(project, coroutineScope)
  }
}

@Internal
open class HighlightingNecromancer(
  private val project: Project,
  coroutineScope: CoroutineScope,
) : GravingNecromancer<HighlightingZombie>(
  project,
  coroutineScope,
  NAME,
  HighlightingNecromancy,
) {

  private val spawnedZombies: ConcurrentIntObjectMap<Boolean> = ConcurrentCollectionFactory.createConcurrentIntObjectMap()

  init {
    subscribeDaemonFinished(project, coroutineScope)
  }

  override fun turnIntoZombie(recipe: TurningRecipe): HighlightingZombie? {
    if (isEnabled()) {
      val markupModel = DocumentMarkupModel.forDocument(recipe.document, recipe.project, false)
      if (markupModel is MarkupModelEx) {
        val colorsScheme = recipe.editor.colorsScheme
        val collector = HighlighterCollector()
        markupModel.processRangeHighlightersOverlappingWith(0, recipe.document.textLength, collector)
        val highlighters = collector.results.map { highlighter ->
          HighlightingLimb(highlighter, getHighlighterLayer(highlighter), colorsScheme)
        }.toList()
        return HighlightingZombie(highlighters)
      }
    }
    return null
  }

  override suspend fun shouldBuryZombie(recipe: TurningRecipe, zombie: FingerprintedZombie<HighlightingZombie>): Boolean {
    val oldZombie = exhumeZombie(recipe.fileId)
    val zombieDisposed = spawnedZombies[recipe.fileId]
    val graveDecision = getGraveDecision(
      newZombie = zombie,
      oldZombie = oldZombie,
      isNewMoreRelevant = zombieDisposed,
    )
    return when (graveDecision) {
      GraveDecision.BURY_NEW -> {
        true
      }
      GraveDecision.REMOVE_OLD -> {
        buryZombie(recipe.fileId, null)
        false
      }
      GraveDecision.KEEP_OLD -> {
        false
      }
    }
  }

  override suspend fun shouldSpawnZombie(recipe: SpawnRecipe): Boolean {
    return isEnabled() && !spawnedZombies.containsKey(recipe.fileId)
  }

  override suspend fun spawnZombie(recipe: SpawnRecipe, zombie: HighlightingZombie?) {
    if (zombie == null) {
      spawnedZombies.put(recipe.fileId, true)
      logFusStatistic(recipe.file, MarkupGraveEvent.NOT_RESTORED_CACHE_MISS)
    } else {
      val markupModel = DocumentMarkupModel.forDocument(recipe.document, project, true)

      // we have to make sure that editor highlighter is created before we start raising zombies
      // because creation of highlighter has a side effect that TextAttributesKey.ourRegistry is filled with corresponding keys
      // (e.g. class loading of org.jetbrains.kotlin.idea.highlighter.KotlinHighlightingColors)
      // without such guarantee there is a risk to get uninitialized fallbackKey in TextAttributesKey.find(externalName)
      // it may lead to incorrect color of highlighters on startup
      recipe.highlighterReady()

      val spawned = spawnZombie(markupModel, recipe, zombie)
      spawnedZombies.put(recipe.fileId, spawned == 0)
      logFusStatistic(recipe.file, MarkupGraveEvent.RESTORED, spawned)
    }
  }

  protected open fun subscribeDaemonFinished(project: Project, coroutineScope: CoroutineScope) {
    // as soon as highlighting kicks in and displays its own range highlighters, remove ones we applied from the on-disk cache,
    // but only after the highlighting finished, to avoid flicker
    project.messageBus.connect(coroutineScope).subscribe(
      DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
      object : DaemonCodeAnalyzer.DaemonListener {
        override fun daemonFinished(fileEditors: Collection<FileEditor>) {
          if (!DumbService.getInstance(project).isDumb) {
            for (fileEditor in fileEditors) {
              if (fileEditor is TextEditor && shouldPutDownActiveZombiesInFile(fileEditor)) {
                val document = fileEditor.editor.document
                val file = FileDocumentManager.getInstance().getFile(document)
                if (file is VirtualFileWithId) {
                  putDownActiveZombiesInFile(file, document)
                }
              }
            }
          }
        }
      },
    )
  }

  protected open fun shouldPutDownActiveZombiesInFile(textEditor: TextEditor): Boolean {
    return textEditor.editor.editorKind == EditorKind.MAIN_EDITOR &&
           DaemonCodeAnalyzerEx.isHighlightingCompleted(textEditor, project)
  }

  protected open fun shouldBuryHighlighter(highlighter: RangeHighlighterEx): Boolean {
    if (StickyLinesModelImpl.isStickyLine(highlighter)) {
      // hack for sticky lines since they are implemented via markupModel
      return true
    }
    if (highlighter.editorFilter != MarkupEditorFilter.EMPTY) {
      // skip highlighters with non-default filter to avoid appearing filtered highlighters
      return false
    }
    val severity = HighlightInfo.fromRangeHighlighter(highlighter)?.severity
    if (severity === HighlightInfoType.SYMBOL_TYPE_SEVERITY ||
        (severity != null && severity > HighlightSeverity.INFORMATION)) {
      // either warning/error or symbol type (e.g., field text attribute)
      return true
    }
    val lineMarker = LineMarkersUtil.getLineMarkerInfo(highlighter)
    // or a line marker with a gutter icon
    return lineMarker?.icon != null
  }

  protected open fun getHighlighterLayer(highlighter: RangeHighlighterEx): Int {
    return highlighter.layer
  }

  protected fun putDownActiveZombiesInFile(file: VirtualFileWithId, document: Document) {
    val replaced = spawnedZombies.replace(file.id, false, true)
    if (!replaced) {
      // no zombie or zombie already disposed
      return
    }
    val markupModel = DocumentMarkupModel.forDocument(document, project, false)
    if (markupModel != null) {
      val zombies = markupModel.allHighlighters.filter { isZombieMarkup(it) }.toList()
      for (highlighter in zombies) {
        highlighter.dispose()
      }
    }
  }

  private suspend fun spawnZombie(markupModel: MarkupModel, recipe: SpawnRecipe, zombie: HighlightingZombie): Int {
    var spawned = 0
    if (recipe.isValid()) {
      // restore highlighters with batches to balance between RA duration and RA count
      val batchSize = RA_BATCH_SIZE
      val batchCount = zombie.limbs().size / batchSize
      for (batchNum in 0 until batchCount) {
        val abortSpawning = readActionBlocking {
          val isValid = recipe.isValid() // ensure document not changed
          if (isValid) {
            for (limbNumInBatch in 0 until batchSize) {
              val limbNum = batchNum * batchSize + limbNumInBatch
              createRangeHighlighter(markupModel, zombie.limbs()[limbNum])
              spawned++
            }
          }
          !isValid
        }
        if (abortSpawning) {
          return spawned
        }
      }
      readActionBlocking {
        if (recipe.isValid()) {
          for (limbNum in (batchCount * batchSize) until zombie.limbs().size) {
            createRangeHighlighter(markupModel, zombie.limbs()[limbNum])
            spawned++
          }
        }
      }
      assert(spawned == zombie.limbs().size) {
        "expected: ${zombie.limbs().size}, actual: $spawned"
      }
    }
    return spawned
  }

  private fun createRangeHighlighter(markupModel: MarkupModel, limb: HighlightingLimb) {
    ThreadingAssertions.assertReadAccess()

    val highlighter = if (limb.textAttributesKey != null) {
      // re-read TextAttributesKey because it might be read too soon, with its myFallbackKey uninitialized.
      // (still store TextAttributesKey by instance, instead of String, to intern its external name)
      // see recipe.highlighterReady()
      val key = TextAttributesKey.find(limb.textAttributesKey.externalName)
      markupModel.addRangeHighlighter(
        key,
        limb.startOffset,
        limb.endOffset,
        limb.layer,
        limb.targetArea,
      )
    } else {
      markupModel.addRangeHighlighter(
        limb.startOffset,
        limb.endOffset,
        limb.layer,
        limb.textAttributes, // TODO: why attributes is null sometimes?
        limb.targetArea,
      )
    }
    highlighter.putUserData(IS_ZOMBIE, true)
    if (limb.gutterIcon != null) {
      highlighter.gutterIconRenderer = ZombieIcon(limb.gutterIcon)
    }
    if (StickyLinesModelImpl.isStickyLine(highlighter)) {
      StickyLinesModelImpl.skipInAllEditors(highlighter)
    }
  }

  private fun logFusStatistic(file: VirtualFile, event: MarkupGraveEvent, restoredCount: Int = 0) {
    logEditorMarkupGrave(project, file, event, restoredCount)
  }

  @TestOnly
  private fun clearSpawnedZombies() {
    spawnedZombies.clear()
  }

  private inner class HighlighterCollector : CommonProcessors.CollectProcessor<RangeHighlighterEx>() {
    override fun accept(highlighter: RangeHighlighterEx?): Boolean {
      return highlighter != null && shouldBuryHighlighter(highlighter)
    }
  }

  private enum class GraveDecision {
    BURY_NEW,
    KEEP_OLD,
    REMOVE_OLD,
  }

  private fun getGraveDecision(
    newZombie: FingerprintedZombie<HighlightingZombie>,
    oldZombie: FingerprintedZombie<HighlightingZombie>?,
    isNewMoreRelevant: Boolean?,
  ): GraveDecision {
    return when {
      // put zombie's limbs
      oldZombie == null && !newZombie.isEmpty() -> GraveDecision.BURY_NEW
      // no a limb to put in grave
      oldZombie == null -> GraveDecision.KEEP_OLD
      // fresh limbs
      oldZombie.fingerprint() != newZombie.fingerprint() && !newZombie.isEmpty() -> GraveDecision.BURY_NEW
      // graved zombie is rotten and there is no a limb to bury
      oldZombie.fingerprint() != newZombie.fingerprint() -> GraveDecision.REMOVE_OLD
      // graved zombie is still fresh
      newZombie.isEmpty() -> GraveDecision.KEEP_OLD
      // should never happen. the file is closed without being opened before
      isNewMoreRelevant == null -> GraveDecision.BURY_NEW
      // limbs form complete zombie
      isNewMoreRelevant -> GraveDecision.BURY_NEW
      else -> GraveDecision.KEEP_OLD
    }
  }

  private fun FingerprintedZombie<HighlightingZombie>.isEmpty(): Boolean {
    return zombie().limbs().isEmpty()
  }

  private class ZombieIcon(private val icon: Icon) : GutterIconRenderer() {
    override fun equals(other: Any?): Boolean = false
    override fun hashCode(): Int = 0
    override fun getIcon(): Icon = icon
  }

  companion object {
    private const val NAME = "graved-highlighting"

    private const val RA_BATCH_SIZE = 3_000

    private val IS_ZOMBIE = Key.create<Boolean>("IS_ZOMBIE")

    private fun isEnabled(): Boolean {
      return Registry.`is`("cache.highlighting.markup.on.disk", true)
    }

    @JvmStatic
    fun isZombieMarkup(highlighter: RangeMarker): Boolean {
      return highlighter.getUserData(IS_ZOMBIE) != null
    }

    @JvmStatic
    fun unmarkZombieMarkup(highlighter: RangeMarker) {
      highlighter.putUserData(IS_ZOMBIE, null)
    }

    @JvmStatic
    @TestOnly
    fun runInEnabled(runnable: Runnable) {
      val wasEnabled = isEnabled()
      Registry.get("cache.highlighting.markup.on.disk").setValue(true)
      try {
        runnable.run()
      } finally {
        Registry.get("cache.highlighting.markup.on.disk").setValue(wasEnabled)
      }
    }

    @JvmStatic
    @TestOnly
    fun clearSpawnedZombies(project: Project) {
      val service = project.service<Necropolis>()
      (service.necromancerByName(NAME) as HighlightingNecromancer).clearSpawnedZombies()
    }
  }
}
