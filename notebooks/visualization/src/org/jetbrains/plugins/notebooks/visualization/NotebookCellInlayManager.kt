package org.jetbrains.plugins.notebooks.visualization

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.EventDispatcher
import com.intellij.util.SmartList
import com.intellij.util.concurrency.ThreadingAssertions
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.notebooks.ui.isFoldingEnabledKey
import org.jetbrains.plugins.notebooks.visualization.ui.EditorCell
import org.jetbrains.plugins.notebooks.visualization.ui.EditorCellEventListener
import org.jetbrains.plugins.notebooks.visualization.ui.EditorCellEventListener.*
import org.jetbrains.plugins.notebooks.visualization.ui.EditorCellView
import org.jetbrains.plugins.notebooks.visualization.ui.keepScrollingPositionWhile
import java.util.*
import kotlin.math.max
import kotlin.math.min

class NotebookCellInlayManager private constructor(
  val editor: EditorImpl,
) : Disposable, NotebookIntervalPointerFactory.ChangeListener {
  private val notebookCellLines = NotebookCellLines.get(editor)

  private var initialized = false

  private var _cells = mutableListOf<EditorCell>()

  val cells: List<EditorCell> get() = _cells.toList()

  /**
   * Listens for inlay changes (called after all inlays are updated). Feel free to convert it to the EP if you need another listener
   */
  var changedListener: InlaysChangedListener? = null

  private val cellEventListeners = EventDispatcher.create(EditorCellEventListener::class.java)

  private val invalidationListeners = mutableListOf<Runnable>()

  private var valid = false

  override fun dispose() {}

  fun getCellForInterval(interval: NotebookCellLines.Interval): EditorCell =
    _cells[interval.ordinal]

  fun updateAllOutputs() {
    _cells.forEach {
      it.view?.updateOutputs()
    }
  }

  private fun updateAll() {
    if (initialized) {
      updateConsequentInlays(0..editor.document.lineCount, force = false)
    }
  }

  fun forceUpdateAll() = runInEdt {
    if (initialized) {
      updateConsequentInlays(0..editor.document.lineCount, force = true)
    }
  }

  fun update(pointers: Collection<NotebookIntervalPointer>) = runInEdt {
    val linesList = pointers.mapNotNullTo(mutableListOf()) { it.get()?.lines }
    linesList.sortBy { it.first }
    linesList.mergeAndJoinIntersections(listOf())

    for (lines in linesList) {
      updateConsequentInlays(0..editor.document.lineCount, force = false)
    }
  }

  fun update(cell: EditorCell) = runInEdt {
    update(cell.intervalPointer)
  }

  fun update(pointer: NotebookIntervalPointer) = runInEdt {
    update(SmartList(pointer))
  }

  private fun addViewportChangeListener() {
    editor.scrollPane.viewport.addChangeListener {
      _cells.forEach {
        it.onViewportChange()
      }
    }
  }

  private fun initialize() {
    // TODO It would be a cool approach to add inlays lazily while scrolling.

    editor.putUserData(key, this)

    handleRefreshedDocument()

    val connection = ApplicationManager.getApplication().messageBus.connect(editor.disposable)
    connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
      updateAll()
    })
    connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
      updateAll()
    })

    addViewportChangeListener()

    editor.foldingModel.addListener(object : FoldingListener {
      override fun onFoldProcessingEnd() {
        invalidateCells()
      }
    }, editor.disposable)

    initialized = true

    setupFoldingListener()
    setupSelectionUI()
  }

  private fun setupSelectionUI() {
    editor.caretModel.addCaretListener(object : CaretListener {
      override fun caretPositionChanged(event: CaretEvent) {
        updateSelection()
      }
    })
  }

  private fun updateSelection() {
    val selectionModel = editor.cellSelectionModel ?: error("The selection model is supposed to be installed")
    val selectedCells = selectionModel.selectedCells.map { it.ordinal }
    for (cell in cells) {
      cell.selected = cell.intervalPointer.get()?.ordinal in selectedCells
    }
  }

  private fun setupFoldingListener() {
    val foldingModel = editor.foldingModel
    foldingModel.addListener(object : FoldingListener {

      val changedRegions = LinkedList<FoldRegion>()
      val removedRegions = LinkedList<FoldRegion>()

      override fun beforeFoldRegionDisposed(region: FoldRegion) {
        removedRegions.add(region)
      }

      override fun beforeFoldRegionRemoved(region: FoldRegion) {
        removedRegions.add(region)
      }

      override fun onFoldRegionStateChange(region: FoldRegion) {
        changedRegions.add(region)
      }

      override fun onFoldProcessingEnd() {
        val changedRegions = changedRegions.filter { it.getUserData(FOLDING_MARKER_KEY) == true }
        this.changedRegions.clear()
        val removedRegions = removedRegions.toList()
        this.removedRegions.clear()
        changedRegions.forEach { region ->
          editorCells(region).forEach {
            it.visible = region.isExpanded
          }
        }
        removedRegions.forEach { region ->
          editorCells(region).forEach {
            it.visible = true
          }
        }
      }
    }, editor.disposable)
  }

  private fun editorCells(region: FoldRegion): List<EditorCell> = _cells.filter { cell ->
    val startOffset = editor.document.getLineStartOffset(cell.intervalPointer.get()!!.lines.first)
    val endOffset = editor.document.getLineEndOffset(cell.intervalPointer.get()!!.lines.last)
    startOffset >= region.startOffset && endOffset <= region.endOffset
  }

  private fun handleRefreshedDocument() {
    ThreadingAssertions.softAssertReadAccess()
    _cells.forEach {
      Disposer.dispose(it)
    }
    val pointerFactory = NotebookIntervalPointerFactory.get(editor)

    //Perform inlay init in batch mode
    editor.inlayModel.execute(true) {
      _cells = notebookCellLines.intervals.map { interval ->
        createCell(pointerFactory.create(interval))
      }.toMutableList()
    }

    _cells.forEach {
      it.view?.postInitInlays()
    }

    updateCellsFolding(_cells)

    cellEventListeners.multicaster.onEditorCellEvents(_cells.map { CellCreated(it) })
    inlaysChanged()
  }

  private fun createCell(interval: NotebookIntervalPointer) = EditorCell(editor, interval) { cell ->
    EditorCellView(editor, notebookCellLines, cell, this).also { Disposer.register(cell, it) }
  }.also { Disposer.register(this, it) }

  private fun ensureInlaysAndHighlightersExist(matchingCellsBeforeChange: List<NotebookCellLines.Interval>, logicalLines: IntRange) {
    val interestingRange =
      matchingCellsBeforeChange
        .map { it.lines }
        .takeIf { it.isNotEmpty() }
        ?.let { min(logicalLines.first, it.first().first)..max(it.last().last, logicalLines.last) }
      ?: logicalLines
    updateConsequentInlays(interestingRange)
  }

  private fun inlaysChanged() {
    changedListener?.inlaysChanged()
  }

  private fun updateConsequentInlays(interestingRange: IntRange, force: Boolean = false) {
    ThreadingAssertions.softAssertReadAccess()
    keepScrollingPositionWhile(editor) {
      val matchingIntervals = notebookCellLines.getMatchingCells(interestingRange)

      val matchingCells = matchingIntervals.map {
        _cells[it.ordinal]
      }
      matchingCells.forEach {
        it.update(force)
      }

      updateCellsFolding(matchingCells)

      inlaysChanged()
    }
  }

  private fun updateCellsFolding(editorCells: List<EditorCell>) {
    val foldingModel = editor.foldingModel
    foldingModel.runBatchFoldingOperation({
                                            editorCells.forEach { cell ->
                                              cell.view?.updateCellFolding()
                                            }
                                          }, true, false)
  }

  private fun NotebookCellLines.getMatchingCells(logicalLines: IntRange): List<NotebookCellLines.Interval> =
    mutableListOf<NotebookCellLines.Interval>().also { result ->
      // Since inlay appearance may depend from neighbour cells, adding one more cell at the start and at the end.
      val iterator = intervalsIterator(logicalLines.first)
      if (iterator.hasPrevious()) iterator.previous()
      for (interval in iterator) {
        result.add(interval)
        if (interval.lines.first > logicalLines.last) break
      }
    }

  @TestOnly
  fun updateControllers(matchingCells: List<NotebookCellLines.Interval>, logicalLines: IntRange) {
    ensureInlaysAndHighlightersExist(matchingCells, logicalLines)
  }

  companion object {
    @JvmStatic
    fun install(editor: EditorImpl) {
      val notebookCellInlayManager = NotebookCellInlayManager(editor).also { Disposer.register(editor.disposable, it) }
      editor.putUserData(isFoldingEnabledKey, Registry.`is`("jupyter.editor.folding.cells"))
      NotebookIntervalPointerFactory.get(editor).changeListeners.addListener(notebookCellInlayManager, editor.disposable)
      notebookCellInlayManager.initialize()
    }

    @JvmStatic
    fun get(editor: Editor): NotebookCellInlayManager? = key.get(editor)
    val FOLDING_MARKER_KEY = Key<Boolean>("jupyter.folding.paragraph")
    private val key = Key.create<NotebookCellInlayManager>(NotebookCellInlayManager::class.java.name)
  }

  override fun onUpdated(event: NotebookIntervalPointersEvent) {
    val events = mutableListOf<EditorCellEvent>()
    for (change in event.changes) {
      when (change) {
        is NotebookIntervalPointersEvent.OnEdited -> {
          val cell = _cells[change.intervalAfter.ordinal]
          cell.updateInput()
          events.add(CellUpdated(cell))
        }
        is NotebookIntervalPointersEvent.OnInserted -> {
          change.subsequentPointers.forEach {
            val editorCell = createCell(it.pointer)
            addCell(it.interval.ordinal, editorCell, events)
          }
        }
        is NotebookIntervalPointersEvent.OnRemoved -> {
          change.subsequentPointers.reversed().forEach {
            val index = it.interval.ordinal
            removeCell(index, events)
          }
        }
        is NotebookIntervalPointersEvent.OnSwapped -> {
          val firstCell = _cells[change.firstOrdinal]
          val first = firstCell.intervalPointer
          val secondCell = _cells[change.secondOrdinal]
          firstCell.intervalPointer = secondCell.intervalPointer
          secondCell.intervalPointer = first
          firstCell.update(force = false)
          secondCell.update(force = false)
        }
      }
    }
    event.changes.filterIsInstance<NotebookIntervalPointersEvent.OnInserted>().forEach { change ->
      fixInlaysOffsetsAfterNewCellInsert(change)
    }
    cellEventListeners.multicaster.onEditorCellEvents(events)
    inlaysChanged()

    checkInlayOffsets()
  }

  private fun checkInlayOffsets() {
    val inlaysOffsets = buildSet {
      for (cell in _cells) {
        add(editor.document.getLineStartOffset(cell.interval.lines.first))
        add(editor.document.getLineEndOffset(cell.interval.lines.last))
      }
    }
    val wronglyPlacedInlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
      .filter { it.offset !in inlaysOffsets }
    if (wronglyPlacedInlays.isNotEmpty()) {
      error("Expected offsets: $inlaysOffsets. Wrongly placed inlays: $wronglyPlacedInlays")
    }
  }

  private fun fixInlaysOffsetsAfterNewCellInsert(change: NotebookIntervalPointersEvent.OnInserted) {
    val prevCellIndex = change.subsequentPointers.first().interval.ordinal - 1
    if (prevCellIndex >= 0) {
      val prevCell = getCell(prevCellIndex)
      prevCell.update()
    }
  }

  private fun addCell(index: Int, editorCell: EditorCell, events: MutableList<EditorCellEvent>) {
    _cells.add(index, editorCell)
    events.add(CellCreated(editorCell))
    invalidateCells()
  }

  private fun removeCell(index: Int, events: MutableList<EditorCellEvent>) {
    val removed = _cells.removeAt(index)
    Disposer.dispose(removed)
    events.add(CellRemoved(removed))
    invalidateCells()
  }

  fun addCellEventsListener(editorCellEventListener: EditorCellEventListener, disposable: Disposable) {
    cellEventListeners.addListener(editorCellEventListener, disposable)
  }

  fun getCell(index: Int): EditorCell {
    return cells[index]
  }

  fun invalidateCells() {
    if (valid) {
      valid = false
      invalidationListeners.forEach { it.run() }
    }
  }

  fun validateCells() {
    if (!valid) {
      _cells.forEach {
        it.view?.also { view ->
          view.bounds = view.calculateBounds()
          view.validate()
        }
      }
      valid = true
    }
  }

  fun onInvalidate(function: () -> Unit) {
    invalidationListeners.add(function)
  }
}