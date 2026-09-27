/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.util

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** This fragment's own view model, made from the application on first use. */
inline fun <reified VM : ViewModel> Fragment.appViewModels(crossinline create: (Application) -> VM): Lazy<VM> =
    viewModels { viewModelFactory { initializer { create(requireActivity().application) } } }

/** The activity's view model, shared by its fragments, made from the application on first use. */
inline fun <reified VM : ViewModel> Fragment.activityAppViewModels(crossinline create: (Application) -> VM): Lazy<VM> =
    activityViewModels { viewModelFactory { initializer { create(requireActivity().application) } } }
