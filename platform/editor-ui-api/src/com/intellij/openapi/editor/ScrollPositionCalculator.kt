// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor

import org.jetbrains.annotations.ApiStatus.Experimental
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JScrollPane

@Experimental
interface ScrollPositionCalculator {
  fun calcOffsetsToScroll(editor: Editor, targetLocation: Point, scrollType: ScrollType, viewRect: Rectangle, scrollPane: JScrollPane): Point {
    var adjustedScrollType = scrollType
    if (editor.settings.isRefrainFromScrolling && viewRect.contains(targetLocation)) {
      if (scrollType == ScrollType.CENTER || scrollType == ScrollType.CENTER_DOWN || scrollType == ScrollType.CENTER_UP) {
        adjustedScrollType = ScrollType.RELATIVE
      }
    }
    val hOffset = getHorizontalOffset(editor, targetLocation, adjustedScrollType, viewRect, scrollPane)
    val vOffset = getVerticalOffset(editor, targetLocation, adjustedScrollType, viewRect, scrollPane)
    return Point(hOffset, vOffset)
  }

  fun getHorizontalOffset(editor: Editor, targetLocation: Point, scrollType: ScrollType, viewRect: Rectangle, scrollPane: JScrollPane): Int
  fun getVerticalOffset(editor: Editor, targetLocation: Point, scrollType: ScrollType, viewRect: Rectangle, scrollPane: JScrollPane): Int}