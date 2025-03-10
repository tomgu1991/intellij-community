// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.intellij.plugins.markdown.fileActions.export

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextComponentAccessors
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.RecentsManager
import com.intellij.ui.TextFieldWithHistoryWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowsRange
import com.intellij.ui.layout.selected
import org.intellij.plugins.markdown.MarkdownBundle
import org.intellij.plugins.markdown.fileActions.MarkdownFileActionFormat
import org.intellij.plugins.markdown.fileActions.MarkdownFileActionsBaseDialog
import org.intellij.plugins.markdown.fileActions.utils.MarkdownFileEditorUtils
import org.intellij.plugins.markdown.fileActions.utils.MarkdownImportExportUtils
import org.intellij.plugins.markdown.fileActions.utils.MarkdownImportExportUtils.notifyAndRefreshIfExportSuccess
import org.intellij.plugins.markdown.fileActions.utils.MarkdownImportExportUtils.validateTargetDir
import org.intellij.plugins.markdown.ui.MarkdownNotifications
import org.intellij.plugins.markdown.ui.preview.MarkdownPreviewFileEditor
import org.intellij.plugins.markdown.ui.preview.jcef.HtmlExporter
import org.intellij.plugins.markdown.ui.preview.jcef.HtmlResourceSavingSettings
import org.intellij.plugins.markdown.ui.preview.jcef.MarkdownJCEFHtmlPanel
import org.jetbrains.annotations.NonNls
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.io.File
import java.util.function.BiConsumer
import javax.swing.JCheckBox
import javax.swing.JTextField

internal class MarkdownHtmlExportProvider : MarkdownExportProvider {
  private lateinit var saveImagesCheckbox: JCheckBox
  private lateinit var resourceDirField: TextFieldWithHistoryWithBrowseButton

  override val formatDescription: MarkdownFileActionFormat
    get() = format

  override fun exportFile(project: Project, mdFile: VirtualFile, outputFile: String) {
    saveSettings(project)

    val preview = MarkdownFileEditorUtils.findMarkdownPreviewEditor(project, mdFile, true) ?: return
    val htmlPanel = preview.getUserData(MarkdownPreviewFileEditor.PREVIEW_BROWSER)?.get() ?: return

    if (htmlPanel is MarkdownJCEFHtmlPanel) {
      htmlPanel.saveHtml(outputFile, service<MarkdownHtmlExportSettings>().getResourceSavingSettings(), project) { path, ok ->
        if (ok) {
          notifyAndRefreshIfExportSuccess(File(path), project)
        }
        else {
          MarkdownNotifications.showError(
            project,
            id = MarkdownExportProvider.Companion.NotificationIds.exportFailed,
            message = MarkdownBundle.message("markdown.export.failure.msg", File(path).name)
          )
        }
      }
    }
  }

  override fun validate(project: Project, file: VirtualFile): String? {
    val preview = MarkdownFileEditorUtils.findMarkdownPreviewEditor(project, file, true)
    if (preview == null || !MarkdownImportExportUtils.isJCEFPanelOpen(preview)) {
      return MarkdownBundle.message("markdown.export.validation.failure.msg", formatDescription.formatName)
    }
    return null
  }

  override fun Panel.createSettingsComponent(project: Project, suggestedTargetFile: File): RowsRange {
    createResourceDirField(project, suggestedTargetFile)

    return rowsRange {
      row {
        saveImagesCheckbox = checkBox(MarkdownBundle.message("markdown.export.to.html.save.images.checkbox"))
          .onChanged { saveSettings(project) }
          .gap(RightGap.SMALL)
          .applyToComponent {
            toolTipText = MarkdownBundle.message("markdown.export.dialog.checkbox.tooltip")
            isSelected = service<MarkdownHtmlExportSettings>().getResourceSavingSettings().isSaved
          }.component
        cell(resourceDirField)
          .align(AlignX.FILL)
          .validationOnApply { validateTargetDir(it) }
          .focused()
          .enabledIf(saveImagesCheckbox.selected)
        addSettingsListeners(project)
      }
    }
  }

  private fun createResourceDirField(project: Project, suggestedTargetFile: File) {
    resourceDirField = TextFieldWithHistoryWithBrowseButton().apply {
      setTextFieldPreferredWidth(MarkdownFileActionsBaseDialog.MAX_PATH_LENGTH)

      val resDirRecent = RecentsManager.getInstance(project).getRecentEntries(IMAGE_DIR_RESENT_KEYS)
      if (resDirRecent != null) {
        childComponent.history = resDirRecent
      }

      childComponent.text = FileUtil.join(suggestedTargetFile.parent, suggestedTargetFile.nameWithoutExtension)

      addBrowseFolderListener(
        MarkdownBundle.message("markdown.import.export.dialog.target.directory"),
        MarkdownBundle.message("markdown.import.export.dialog.target.directory.description"),
        project,
        FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        TextComponentAccessors.TEXT_FIELD_WITH_HISTORY_WHOLE_TEXT
      )
    }
  }

  private fun addSettingsListeners(project: Project) {
    resourceDirField.childComponent.textEditor.addFocusListener(SaveSettingsListener(project))
  }

  private fun saveSettings(project: Project) {
    val imageDir = resourceDirField.childComponent.text
    val exportSettings = service<MarkdownHtmlExportSettings>()
    exportSettings.saveResources = saveImagesCheckbox.isSelected
    exportSettings.resourceDirectory = imageDir
    RecentsManager.getInstance(project).registerRecentEntry(IMAGE_DIR_RESENT_KEYS, imageDir)
  }

  private fun MarkdownJCEFHtmlPanel.saveHtml(path: String,
                                             resDirPath: HtmlResourceSavingSettings,
                                             project: Project,
                                             resultCallback: BiConsumer<String, Boolean>) {
    cefBrowser.getSource { source ->
      try {
        val file = File(path)
        HtmlExporter(source, resDirPath, project, file).export()
        resultCallback.accept(path, true)
      }
      catch (e: Exception) {
        resultCallback.accept(path, false)
      }
    }
  }

  private inner class SaveSettingsListener(private val project: Project) : FocusListener {
    override fun focusGained(e: FocusEvent?) {}

    override fun focusLost(e: FocusEvent?) {
      val textEditor = e?.component as JTextField
      if (saveImagesCheckbox.isSelected && textEditor.text.isNotEmpty()) {
        saveSettings(project)
      }
    }
  }

  companion object {
    private const val IMAGE_DIR_RESENT_KEYS: @NonNls String = "ImportExportFile.ImageDir.RECENT_KEYS"

    @JvmStatic
    val format = MarkdownFileActionFormat("HTML", "html")
  }
}
