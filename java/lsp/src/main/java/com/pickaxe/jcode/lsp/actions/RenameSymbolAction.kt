/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.lsp.actions

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pickaxe.jcode.actions.ActionData
import com.pickaxe.jcode.actions.ActionItem
import com.pickaxe.jcode.actions.EditorActionItem
import com.pickaxe.jcode.actions.hasRequiredData
import com.pickaxe.jcode.actions.markInvisible
import com.pickaxe.jcode.actions.requireFile
import com.pickaxe.jcode.editor.api.ILspEditor
import com.pickaxe.jcode.resources.R
import com.pickaxe.jcode.utils.DialogUtils
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/**
 * Action which allows the user to rename the symbol at the cursor. The rename is performed by the
 * language server of the file which is currently open (Java or Kotlin).
 *
 * @author Akash Yadav
 */
class RenameSymbolAction : EditorActionItem {

  override val id: String = "ide.editor.lsp.renameSymbol"
  override var label: String = ""
  override var visible: Boolean = true
  override var enabled: Boolean = true
  override var icon: Drawable? = null
  override var requiresUIThread: Boolean = true
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

  override fun prepare(data: ActionData) {
    super.prepare(data)

    val context = data.get(Context::class.java)
    if (context == null || !data.hasRequiredData(CodeEditor::class.java, File::class.java)) {
      markInvisible()
      return
    }

    label = context.getString(R.string.action_rename_symbol)

    visible = isSupported(data.requireFile())
    enabled = visible
  }

  override suspend fun execAction(data: ActionData): Any {
    val context = data.get(Context::class.java) ?: return false
    val editor = data.get(CodeEditor::class.java) ?: return false
    val lspEditor = editor as? ILspEditor ?: return false

    val currentName = wordAtCursor(editor)

    val inputLayout =
        TextInputLayout(context).apply { hint = context.getString(R.string.rename_symbol_hint) }
    val editText =
        TextInputEditText(inputLayout.context).apply {
          setText(currentName)
          setSelection(currentName.length)
          isSingleLine = true
          imeOptions = EditorInfo.IME_ACTION_DONE
        }
    inputLayout.addView(editText)

    val padding = (24 * context.resources.displayMetrics.density).toInt()
    val container =
        FrameLayout(context).apply {
          setPadding(padding, padding / 2, padding, 0)
          addView(inputLayout)
        }

    DialogUtils.newMaterialDialogBuilder(context)
        .setTitle(R.string.action_rename_symbol)
        .setView(container)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val newName = editText.text?.toString()?.trim().orEmpty()
          if (newName.isNotEmpty() && newName != currentName) {
            lspEditor.rename(newName)
          }
        }
        .show()

    return true
  }

  private fun isSupported(file: File): Boolean {
    val name = file.name.lowercase()
    return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")
  }

  private fun wordAtCursor(editor: CodeEditor): String {
    val content = editor.text
    val cursor = editor.cursor
    val line = content.getLineString(cursor.leftLine)

    var start = cursor.leftColumn.coerceAtMost(line.length)
    var end = start
    while (start > 0 && isIdentifierPart(line[start - 1])) {
      start--
    }
    while (end < line.length && isIdentifierPart(line[end])) {
      end++
    }

    return line.substring(start, end)
  }

  private fun isIdentifierPart(c: Char): Boolean =
      c.isLetterOrDigit() || c == '_' || c == '$'
}
