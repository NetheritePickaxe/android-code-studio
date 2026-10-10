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
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.inflater.internal.adapters

import com.google.android.material.textview.MaterialTextView
import com.pickaxe.jcode.annotations.inflater.ViewAdapter
import com.pickaxe.jcode.annotations.uidesigner.IncludeInDesigner
import com.pickaxe.jcode.annotations.uidesigner.IncludeInDesigner.Group.GOOGLE
import com.pickaxe.jcode.inflater.AttributeHandlerScope
import com.pickaxe.jcode.inflater.IView
import com.pickaxe.jcode.inflater.models.UiWidget
import com.pickaxe.jcode.resources.R.drawable
import com.pickaxe.jcode.resources.R.string

/** MaterialTextView adapter with Material Design 3 support. */
@ViewAdapter(MaterialTextView::class)
@IncludeInDesigner(group = GOOGLE)
open class MaterialTextViewAdapter<T : MaterialTextView> : TextViewAdapter<T>() {

  override fun createUiWidgets(): List<UiWidget> {
    return listOf(
        UiWidget(
            MaterialTextView::class.java,
            string.widget_text_view,
            drawable.ic_widget_text_view,
        )
    )
  }

  override fun createAttrHandlers(create: (String, AttributeHandlerScope<T>.() -> Unit) -> Unit) {
    super.createAttrHandlers(create)

    // Material Design 3 text attributes - handle both with and without namespace
    create("textAppearance") {
      // For now, we'll skip textAppearance as it requires more complex parsing
      // This can be enhanced later if needed
    }

    create("textColor") {
      val color = parseColor(context, value)
      view.setTextColor(color)
    }

    create("textSize") {
      val size = parseDimensionF(context, value)
      if (size > 0) view.textSize = size
    }

    create("textStyle") {
      val style = parseTextStyle(value)
      view.setTypeface(null, style)
    }
  }

  override fun applyBasic(view: IView) {
    super.applyBasic(view)

    // Apply M3 default styling
    val textView = view.view as MaterialTextView
    textView.text = "Material Text"
    textView.textSize = 14f // 14sp for M3 body text
  }
}
