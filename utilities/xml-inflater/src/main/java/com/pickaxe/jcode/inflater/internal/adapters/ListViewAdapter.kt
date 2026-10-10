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

import android.R.layout
import android.widget.ArrayAdapter
import android.widget.ListView
import com.blankj.utilcode.util.SizeUtils
import com.pickaxe.jcode.annotations.inflater.ViewAdapter
import com.pickaxe.jcode.annotations.uidesigner.IncludeInDesigner
import com.pickaxe.jcode.annotations.uidesigner.IncludeInDesigner.Group.WIDGETS
import com.pickaxe.jcode.inflater.AttributeHandlerScope
import com.pickaxe.jcode.inflater.models.UiWidget
import com.pickaxe.jcode.resources.R.drawable
import com.pickaxe.jcode.resources.R.string

/**
 * Attribute adapter for [ListView].
 *
 * @author Akash Yadav
 */
@ViewAdapter(ListView::class)
@IncludeInDesigner(group = WIDGETS)
open class ListViewAdapter<T : ListView> : AbsListViewAdapter<T>() {

  override fun createAttrHandlers(create: (String, AttributeHandlerScope<T>.() -> Unit) -> Unit) {
    super.createAttrHandlers(create)
    create("divider") { view.divider = parseDrawable(context, value) }
    create("dividerHeight") {
      view.dividerHeight = parseDimension(context, value, SizeUtils.dp2px(1f))
    }
    create("entries") {
      val entries = parseStringArray(value)
      view.adapter = ArrayAdapter(context, layout.simple_list_item_1, entries)
    }
    create("footerDividersEnabled") { view.setFooterDividersEnabled(parseBoolean(value)) }
    create("headerDividersEnabled") { view.setHeaderDividersEnabled(parseBoolean(value)) }
  }

  override fun createUiWidgets(): List<UiWidget> {
    return listOf(
        UiWidget(ListView::class.java, string.widget_listview, drawable.ic_widget_list_view)
    )
  }
}
