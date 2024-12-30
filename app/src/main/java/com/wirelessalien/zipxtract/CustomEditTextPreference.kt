/*
 *  Copyright (C) 2023  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wirelessalien.zipxtract

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference

class CustomEditTextPreference(context: Context, attrs: AttributeSet?) : EditTextPreference(context, attrs) {

    private val basePath = "/storage/emulated/0/"

    override fun getText(): String? {
        val text = super.getText()
        return if (text != null && text.startsWith(basePath)) {
            text.removePrefix(basePath)
        } else {
            text
        }
    }

    override fun setText(text: String?) {
        super.setText(basePath + (text ?: ""))
    }
}