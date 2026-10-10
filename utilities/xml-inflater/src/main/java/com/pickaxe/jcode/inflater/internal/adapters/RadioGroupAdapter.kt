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

import android.widget.RadioButton
import android.widget.RadioGroup
import com.pickaxe.jcode.annotations.uidesigner.IncludeInDesigner
import com.pickaxe.jcode.annotations.uidesigner.IncludeInDesigner.Group.LAYOUTS
import com.pickaxe.jcode.inflater.AttributeHandlerScope
import com.pickaxe.jcode.inflater.IView
import com.pickaxe.jcode.inflater.IViewGroup
import com.pickaxe.jcode.inflater.models.UiWidget
import com.pickaxe.jcode.resources.R.drawable
import com.pickaxe.jcode.resources.R.string

/**
 * View adapter for [RadioGroup].
 *
 * @author Akash Yadav
 */
@com.pickaxe.jcode.annotations.inflater.ViewAdapter(forView = RadioGroup::class)
@IncludeInDesigner(group = LAYOUTS)
open class RadioGroupAdapter<T : RadioGroup> : LinearLayoutAdapter<T>() {

  override fun createAttrHandlers(create: (String, AttributeHandlerScope<T>.() -> Unit) -> Unit) {
    super.createAttrHandlers(create)
    create("checkedButton") { view.check(parseId(file.resName, value, -1)) }
  }

  override fun createUiWidgets(): List<UiWidget> {
    return listOf(
        UiWidget(RadioGroup::class.java, string.widget_radio_group, drawable.ic_widget_radio_group)
    )
  }

  override fun canAcceptChild(view: IViewGroup, child: IView?, name: String): Boolean {
    return name == RadioButton::class.java.name
  }
}
