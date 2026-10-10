/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.pickaxe.jcode.actions.etc

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pickaxe.jcode.actions.ActionData
import com.pickaxe.jcode.actions.ActionItem
import com.pickaxe.jcode.actions.ActionMenu
import com.pickaxe.jcode.actions.EditorActivityAction
import com.pickaxe.jcode.editor.schemes.IDEColorSchemeProvider
import com.pickaxe.jcode.preferences.internal.EditorPreferences
import com.pickaxe.jcode.resources.R
import com.pickaxe.jcode.tasks.launchAsyncWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pickaxe.jcode.editor.schemes.IDEColorScheme
import com.pickaxe.jcode.editor.ui.IDEEditor

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ColorSchemeMenu(context: Context, override val order: Int) :
    EditorActivityAction(), ActionMenu {

    override val children: MutableSet<ActionItem> = mutableSetOf()
    override val id: String = "ide.editor.color.scheme"

    init {
        label = context.getString(R.string.idepref_editor_colorScheme)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_color_scheme)
        val schemes = IDEColorSchemeProvider.list()
        schemes.forEachIndexed { index, scheme ->
            addAction(ColorSchemeAction(context, scheme, index))
        }
        addAction(ReloadColorSchemesAction(context, schemes.size))
    }

    override fun prepare(data: ActionData) {
        super<EditorActivityAction>.prepare(data)
        super<ActionMenu>.prepare(data)
        val currentScheme = EditorPreferences.colorScheme
        children.forEach { item ->
            if (item is ColorSchemeAction) {
                item.visible = true
            }
        }
        
      if (!visible) {
        return
      }
      
      val editor = data.get(IDEEditor::class.java)
      if (editor == null) {
        visible = false
        enabled = false
        return
      }
      
      if (!editor.isEditable) {
        visible = false
        enabled = false
        return
      }
      
      visible = true
      enabled = true
    }
}

class ColorSchemeAction(
    context: Context,
    private val scheme: IDEColorScheme,
    override val order: Int
) : EditorActivityAction() {

    override val id: String = "ide.editor.colorScheme.${scheme.key}"
    override var requiresUIThread: Boolean = true

    init {
        label = scheme.name
    }

    override suspend fun execAction(data: ActionData): Boolean {
        EditorPreferences.colorScheme = scheme.key
        
        val context = data.requireActivity()
        context.lifecycleScope.launchAsyncWithProgress(
            context = Dispatchers.Default,
            configureFlashbar = { builder, _ -> 
                builder.message(R.string.please_wait) 
            },
        ) { flashbar, _ ->
            IDEColorSchemeProvider.reload()
            withContext(Dispatchers.Main) { 
                flashbar.dismiss()
            }
        }
        
        return true
    }
}
