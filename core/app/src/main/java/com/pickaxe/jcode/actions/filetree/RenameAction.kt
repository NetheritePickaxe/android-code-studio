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

package com.pickaxe.jcode.actions.filetree

import android.content.Context
import android.view.LayoutInflater
import com.blankj.utilcode.util.FileUtils
import com.pickaxe.jcode.R
import com.pickaxe.jcode.actions.ActionData
import com.pickaxe.jcode.actions.requireFile
import com.pickaxe.jcode.adapters.viewholders.FileTreeViewHolder
import com.pickaxe.jcode.eventbus.events.file.FileRenameEvent
import com.pickaxe.jcode.preferences.databinding.LayoutDialogTextInputBinding
import com.pickaxe.jcode.projects.FileManager
import com.pickaxe.jcode.tasks.launchAsyncWithProgress
import com.pickaxe.jcode.utils.DialogUtils
import com.pickaxe.jcode.utils.FlashType
import com.pickaxe.jcode.utils.flashMessage
import com.unnamed.b.atv.model.TreeNode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/**
 * Action to rename the selected file.
 *
 * @author Akash Yadav
 */
class RenameAction(context: Context, override val order: Int) :
    BaseFileTreeAction(
        context,
        labelRes = R.string.rename_file,
        iconRes = R.drawable.ic_file_rename,
    ) {

  override val id: String = "ide.editor.fileTree.rename"

  override suspend fun execAction(data: ActionData) {
    val context = data.requireActivity()
    val file = data.requireFile()
    val lastHeld = data.getTreeNode()
    val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(context))
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    binding.name.editText!!.hint = context.getString(com.pickaxe.jcode.resources.R.string.new_name)
    binding.name.editText!!.setText(file.name)
    builder.setTitle(com.pickaxe.jcode.resources.R.string.rename_file)
    builder.setMessage(com.pickaxe.jcode.resources.R.string.msg_rename_file)
    builder.setView(binding.root)
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.setPositiveButton(com.pickaxe.jcode.resources.R.string.rename_file) { dialogInterface, _ ->
      dialogInterface.dismiss()
      actionScope.launchAsyncWithProgress(
          configureFlashbar = { builder, cancelChecker ->
            builder.message(com.pickaxe.jcode.resources.R.string.please_wait)
          },
          action = { _, _ ->
            val name: String = binding.name.editText!!.text.toString().trim()
            val renamed = name.length in 1..40 && FileUtils.rename(file, name)

            if (renamed) {
              notifyFileRenamed(file, name, context)
            }

            withContext(Dispatchers.Main) {
              flashMessage(
                  if (renamed) com.pickaxe.jcode.resources.R.string.renamed
                  else com.pickaxe.jcode.resources.R.string.rename_failed,
                  if (renamed) FlashType.SUCCESS else FlashType.ERROR,
              )
              if (!renamed) {
                return@withContext
              }

              if (lastHeld != null) {
                val parent = lastHeld.parent
                parent.deleteChild(lastHeld)
                val node = TreeNode(File(file.parentFile, name))
                node.viewHolder = FileTreeViewHolder(context)
                parent.addChild(node)
                requestExpandNode(parent)
              } else {
                requestFileListing()
              }
            }
          },
      )
    }
    builder.create().show()
  }

  private fun notifyFileRenamed(file: File, name: String, context: Context) {
    val renameEvent = FileRenameEvent(file, File(file.parent, name))

    // Notify FileManager first
    FileManager.onFileRenamed(renameEvent)

    EventBus.getDefault().post(renameEvent.apply { putData(context) })
  }
}
