package com.pickaxe.jcode.activities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModificationData(
    val filePath: String,
    val content: String,
    val isNewFile: Boolean
) : Parcelable