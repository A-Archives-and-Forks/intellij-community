// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.breakpoints.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase
import com.intellij.xdebugger.impl.rpc.XBreakpointId

internal class AddXBreakpointAction(
  private val project: Project,
  private val myType: XBreakpointType<*, *>,
  private val saveCurrentItem: () -> Unit,
  private val selectBreakpoint: (breakpointId: XBreakpointId) -> Unit,
) : AnAction(), DumbAware {

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.icon = myType.enabledIcon
    e.presentation.text = myType.title
  }

  override fun actionPerformed(e: AnActionEvent) {
    saveCurrentItem()
    val breakpoint: XBreakpoint<*>? = myType.addBreakpoint(project, null)
    if (breakpoint is XBreakpointBase<*, *, *>) {
      selectBreakpoint(breakpoint.breakpointId)
    }
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.EDT
  }
}