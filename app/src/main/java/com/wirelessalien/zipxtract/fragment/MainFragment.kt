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

package com.wirelessalien.zipxtract.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.wirelessalien.zipxtract.BuildConfig
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_7Z_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_SPLIT_ZIP_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_TAR_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ZIP_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_COMPRESS_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACT_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACT_CS_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_MULTI_7Z_EXTRACTION_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_MULTI_ZIP_EXTRACTION_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_RAR_EXTRACTION_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.databinding.FragmentMainBinding
import com.wirelessalien.zipxtract.service.Archive7zService
import com.wirelessalien.zipxtract.service.ArchiveSplitZipService
import com.wirelessalien.zipxtract.service.ArchiveTarService
import com.wirelessalien.zipxtract.service.ArchiveZipService
import com.wirelessalien.zipxtract.service.CompressCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date

class MainFragment : Fragment(), FileAdapter.OnItemClickListener, FileAdapter.OnFileLongClickListener {

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var adapter: FileAdapter
    private var isSearchActive: Boolean = false
    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private var currentPath: String? = null
    var actionMode: ActionMode? = null
    private val selectedFiles = mutableListOf<File>()
    private var fileObserver: FileObserver? = null
    private var searchView: SearchView? = null
    private var isObserving: Boolean = false
    private var fileUpdateJob: Job? = null
    private var searchHandler: Handler? = null
    private var searchRunnable: Runnable? = null
    private lateinit var eProgressDialog: AlertDialog
    private lateinit var aProgressDialog: AlertDialog
    private lateinit var progressText: TextView
    private var areFabsVisible: Boolean = false
    private lateinit var eProgressBar: LinearProgressIndicator
    private lateinit var aProgressBar: LinearProgressIndicator
    private var isLargeLayout: Boolean = false
    private lateinit var binding: FragmentMainBinding

    private val extractionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_EXTRACTION_COMPLETE -> {
                    unselectAllFiles()
                    eProgressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.extraction_success), Toast.LENGTH_SHORT).show()
                }
                ACTION_EXTRACTION_ERROR -> {
                    unselectAllFiles()
                    eProgressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                }
                ACTION_EXTRACTION_PROGRESS -> {
                    val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    eProgressBar.progress = progress
                    progressText.text = getString(R.string.extracting_progress, progress)
                }
                ACTION_ARCHIVE_COMPLETE -> {
                    unselectAllFiles()
                    aProgressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.compression_success), Toast.LENGTH_SHORT).show()
                }
                ACTION_ARCHIVE_ERROR -> {
                    unselectAllFiles()
                    aProgressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                }
                ACTION_ARCHIVE_PROGRESS -> {
                    val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    aProgressBar.progress = progress
                    progressText.text = getString(R.string.compressing_progress, progress)
                }
            }
        }
    }

    private fun unselectAllFiles() {
        for (i in 0 until adapter.itemCount) {
            if (selectedFiles.contains(adapter.files[i])) {
                adapter.toggleSelection(i)
            }
        }
        selectedFiles.clear()
        updateActionModeTitle()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
        sortBy = SortBy.valueOf(sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name) ?: SortBy.SORT_BY_NAME.name)
        sortAscending = sharedPreferences.getBoolean("sortAscending", true)

        isLargeLayout = resources.getBoolean(R.bool.large_layout)

        (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)

                val searchItem = menu.findItem(R.id.menu_search)
                searchView = searchItem?.actionView as SearchView?

                // Configure the search view
                searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean {
                        // Perform search when the user submits the query
                        searchFiles(query)
                        return true
                    }

                    override fun onQueryTextChange(newText: String?): Boolean {
                        // Perform search as the user types with a delay
                        searchRunnable?.let { searchHandler?.removeCallbacks(it) }
                        searchRunnable = Runnable {
                            searchFiles(newText)
                        }
                        searchHandler?.postDelayed(searchRunnable!!, 200)
                        return true
                    }
                })
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                val editor = sharedPreferences.edit()
                when (menuItem.itemId) {
                    R.id.menu_sort_by_name -> {
                        sortBy = SortBy.SORT_BY_NAME
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_by_size -> {
                        sortBy = SortBy.SORT_BY_SIZE
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_by_time_of_creation -> {
                        sortBy = SortBy.SORT_BY_MODIFIED
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_by_extension -> {
                        sortBy = SortBy.SORT_BY_EXTENSION
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_ascending -> {
                        sortAscending = true
                        editor.putBoolean("sortAscending", sortAscending)
                    }
                    R.id.menu_sort_descending -> {
                        sortAscending = false
                        editor.putBoolean("sortAscending", sortAscending)
                    }
                }
                editor.apply()
                initRecyclerView()
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        currentPath = arguments?.getString("path")
        searchHandler = Handler(Looper.getMainLooper())

        if (!checkStoragePermissions()) {
            requestForStoragePermissions()
        } else {
            initRecyclerView()
        }

        startFileObserver()

        val filter = IntentFilter().apply {
            addAction(ACTION_EXTRACTION_COMPLETE)
            addAction(ACTION_EXTRACTION_ERROR)
            addAction(ACTION_EXTRACTION_PROGRESS)
            addAction(ACTION_ARCHIVE_COMPLETE)
            addAction(ACTION_ARCHIVE_ERROR)
            addAction(ACTION_ARCHIVE_PROGRESS)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(extractionReceiver, filter)

        extractProgressDialog()
        archiveProgressDialog()

        binding.mainFab.setOnClickListener {
            if (areFabsVisible) {
                hideExtendedFabs()
            } else {
                showExtendedFabs()
            }
        }

        // Set click listeners for extended FABs
        binding.createZipFab.setOnClickListener {
            showZipOptionsDialog()
            hideExtendedFabs()
        }

        binding.create7zFab.setOnClickListener {
            show7zOptionsDialog()
            hideExtendedFabs()
        }

        binding.createTarFab.setOnClickListener {
            showTarOptionsDialog()
            hideExtendedFabs()
        }

        updateCurrentPathChip()

    }

    private fun updateCurrentPathChip() {
        val internalStorage = "Internal Storage"
        val basePath = Environment.getExternalStorageDirectory().absolutePath
        val currentPath = currentPath ?: basePath
        val displayPath = currentPath.replace(basePath, internalStorage).split("/")

        binding.chipGroupPath.removeAllViews()

        var cumulativePath = basePath
        for (part in displayPath) {
            val chip = Chip(requireContext()).apply {
                text = part
                isClickable = true
                isCheckable = false
                val shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(5f * resources.displayMetrics.density)
                    .build()
                setShapeAppearanceModel(shapeAppearanceModel)
                setOnClickListener {
                    cumulativePath = if (part == internalStorage) {
                        basePath
                    } else {
                        "$cumulativePath/$part"
                    }
                    navigateToPath(cumulativePath)
                }
            }
            binding.chipGroupPath.addView(chip)
        }
    }

    private fun navigateToPath(path: String) {
        val fragment = MainFragment().apply {
            arguments = Bundle().apply {
                putString("path", path)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showExtendedFabs() {
        binding.createZipFab.show()
        binding.create7zFab.show()
        binding.createTarFab.show()
        areFabsVisible = true
    }

    private fun hideExtendedFabs() {
        binding.createZipFab.hide()
        binding.create7zFab.hide()
        binding.createTarFab.hide()
        areFabsVisible = false
    }

    private fun show7zOptionsDialog() {
        val fragmentManager = parentFragmentManager
        val newFragment = SevenZOptionDialogFragment.newInstance(adapter)
        if (isLargeLayout) {
            // Show the fragment as a dialog.
            newFragment.show(fragmentManager, "SevenZOptionDialogFragment")
        } else {
            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()
        }
        actionMode?.finish() // Destroy the action mode
    }

    private fun showTarOptionsDialog() {
        val fragmentManager = parentFragmentManager
        val newFragment = TarOptionsDialogFragment.newInstance(adapter)
        if (isLargeLayout) {
            // Show the fragment as a dialog.
            newFragment.show(fragmentManager, "TarOptionDialogFragment")
        } else {
            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()
        }
        actionMode?.finish() // Destroy the action mode
    }

    private fun showZipOptionsDialog() {
        val fragmentManager = parentFragmentManager
        val newFragment = ZipOptionDialogFragment.newInstance(adapter)
        if (isLargeLayout) {
            // Show the fragment as a dialog.
            newFragment.show(fragmentManager, "ZipOptionDialogFragment")
        } else {
            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()
        }
        actionMode?.finish()
    }

    @Suppress("DEPRECATION")
    private fun archiveProgressDialog() {
        val aDialogView = layoutInflater.inflate(R.layout.progress_dialog_archive, null)
        aProgressBar = aDialogView.findViewById(R.id.progressBar)
        progressText = aDialogView.findViewById(R.id.progressText)
        val cancelButton = aDialogView.findViewById<Button>(R.id.cancelButton)
        val backgroundButton = aDialogView.findViewById<Button>(R.id.backgroundButton)

        cancelButton.setOnClickListener {
            val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            val isArchive7zServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == Archive7zService::class.java.name }

            if (isArchive7zServiceRunning) {
                val cancelExtractIntent = Intent(requireContext(), Archive7zService::class.java).apply {
                    action = ACTION_ARCHIVE_7Z_CANCEL
                }
                requireContext().startService(cancelExtractIntent)
            }

            val isArchiveZipServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ArchiveZipService::class.java.name }

            if (isArchiveZipServiceRunning) {
                val cancelZipIntent = Intent(requireContext(), ArchiveZipService::class.java).apply {
                    action = ACTION_ARCHIVE_ZIP_CANCEL
                }
                requireContext().startService(cancelZipIntent)
            }

            val isArchiveSplitZipServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ArchiveSplitZipService::class.java.name }

            if (isArchiveSplitZipServiceRunning) {
                val cancelZipIntent = Intent(requireContext(), ArchiveSplitZipService::class.java).apply {
                    action = ACTION_ARCHIVE_SPLIT_ZIP_CANCEL
                }
                requireContext().startService(cancelZipIntent)
            }

            val isArchiveCompressServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == CompressCsArchiveService::class.java.name }

            if (isArchiveCompressServiceRunning) {
                val cancelZipIntent = Intent(requireContext(), CompressCsArchiveService::class.java).apply {
                    action = ACTION_COMPRESS_CANCEL
                }
                requireContext().startService(cancelZipIntent)
            }

            val isArchiveTarServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ArchiveTarService::class.java.name }

            if (isArchiveTarServiceRunning) {
                val cancelZipIntent = Intent(requireContext(), ArchiveTarService::class.java).apply {
                    action = ACTION_ARCHIVE_TAR_CANCEL
                }
                requireContext().startService(cancelZipIntent)
            }

            aProgressDialog.dismiss()
        }

        backgroundButton.setOnClickListener {
            aProgressDialog.dismiss()
        }

        aProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(aDialogView)
            .setCancelable(false)
            .create()
    }

    @Suppress("DEPRECATION")
    private fun extractProgressDialog() {
        val ePDialogView = layoutInflater.inflate(R.layout.progress_dialog_extract, null)
        eProgressBar = ePDialogView.findViewById(R.id.progressBar)
        progressText = ePDialogView.findViewById(R.id.progressText)
        val cancelButton = ePDialogView.findViewById<Button>(R.id.cancelButton)
        val backgroundButton = ePDialogView.findViewById<Button>(R.id.backgroundButton)

        cancelButton.setOnClickListener {
            val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            val isExtractArchiveServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractArchiveService::class.java.name }

            if (isExtractArchiveServiceRunning) {
                val cancelExtractIntent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
                    action = ACTION_EXTRACT_CANCEL
                }
                requireContext().startService(cancelExtractIntent)
            }

            val isExtractRarServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractRarService::class.java.name }

            if (isExtractRarServiceRunning) {
                val cancelRarIntent = Intent(requireContext(), ExtractRarService::class.java).apply {
                    action = ACTION_RAR_EXTRACTION_CANCEL
                }
                requireContext().startService(cancelRarIntent)
            }

            val isExtractMultipart7zServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractMultipart7zService::class.java.name }

            if (isExtractMultipart7zServiceRunning) {
                val cancelRarIntent = Intent(requireContext(), ExtractMultipart7zService::class.java).apply {
                    action = ACTION_MULTI_7Z_EXTRACTION_CANCEL
                }
                requireContext().startService(cancelRarIntent)
            }

            val isExtractMultipartZipServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractMultipartZipService::class.java.name }

            if (isExtractMultipartZipServiceRunning) {
                val cancelRarIntent = Intent(requireContext(), ExtractMultipartZipService::class.java).apply {
                    action = ACTION_MULTI_ZIP_EXTRACTION_CANCEL
                }
                requireContext().startService(cancelRarIntent)
            }

            val isExtractCsArchiveServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractCsArchiveService::class.java.name }

            if (isExtractCsArchiveServiceRunning) {
                val cancelRarIntent = Intent(requireContext(), ExtractCsArchiveService::class.java).apply {
                    action = ACTION_EXTRACT_CS_CANCEL
                }
                requireContext().startService(cancelRarIntent)
            }

            eProgressDialog.dismiss()
        }

        backgroundButton.setOnClickListener {
            eProgressDialog.dismiss()
        }

        eProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(ePDialogView)
            .setCancelable(false)
            .create()
    }

    override fun onFileLongClick(file: File, view: View) {
        val position = adapter.files.indexOf(file)
        if (position != -1) {
            startActionMode(position)
        }
    }

    override fun onItemClick(file: File, filePath: String) {
        if (actionMode != null) {
            val position = adapter.files.indexOf(file)
            if (position != -1) {
                toggleSelection(position)
                if (getSelectedItemCount() == 0) {
                    actionMode?.finish()
                }
            }
        } else {
            if (file.isDirectory) {
                val fragment = MainFragment().apply {
                    arguments = Bundle().apply {
                        putString("path", file.absolutePath)
                    }
                }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commit()

            } else {
                if (file.extension.equals("tar", ignoreCase = true)) {
                    showCompressorArchiveDialog(filePath)
                } else {
                    showBottomSheetOptions(filePath, file)
                }
            }
        }
    }

    fun startActionMode(position: Int) {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    requireActivity().menuInflater.inflate(R.menu.menu_action, menu)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                    return when (item?.itemId) {
                        R.id.menu_action_select_all -> {
                            selectAllFiles()
                            true
                        }
                        R.id.m_archive_7z -> {
                            val fragmentManager = parentFragmentManager
                            val newFragment = SevenZOptionDialogFragment.newInstance(adapter)
                            if (isLargeLayout) {
                                // Show the fragment as a dialog.
                                newFragment.show(fragmentManager, "SevenZOptionDialogFragment")
                            } else {
                                // Show the fragment fullscreen.
                                val transaction = fragmentManager.beginTransaction()
                                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                                transaction.add(android.R.id.content, newFragment)
                                    .addToBackStack(null)
                                    .commit()
                            }
                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.m_archive_zip -> {
                            val fragmentManager = parentFragmentManager
                            val newFragment = ZipOptionDialogFragment.newInstance(adapter)
                            if (isLargeLayout) {
                                // Show the fragment as a dialog.
                                newFragment.show(fragmentManager, "ZipOptionDialogFragment")
                            } else {
                                // Show the fragment fullscreen.
                                val transaction = fragmentManager.beginTransaction()
                                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                                transaction.add(android.R.id.content, newFragment)
                                    .addToBackStack(null)
                                    .commit()
                            }
                            actionMode?.finish() // Destroy the action mode
                            true
                        }
                        else -> false
                    }
                }

                override fun onDestroyActionMode(mode: ActionMode?) {
                    actionMode = null
//                    clearSelection()
                }
            })
        }

        toggleSelection(position)
        updateActionModeTitle()
    }

    fun toggleSelection(position: Int) {
        val file = adapter.files[position]
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
        } else {
            selectedFiles.add(file)
        }
        adapter.toggleSelection(position)
        updateActionModeTitle()
    }

    fun getSelectedItemCount(): Int {
        return selectedFiles.size
    }

    private fun updateActionModeTitle() {
        val selectedItemCount = getSelectedItemCount()
        actionMode?.title = "$selectedItemCount selected"
    }

    private fun updateProgressBar(progress: Int) {
        binding.progressBar.progress = progress
        if (progress == 100) {
            binding.progressBar.visibility = View.GONE
        } else {
            binding.progressBar.visibility = View.VISIBLE
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true &&
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true) {
            initRecyclerView()
            updateAdapterWithFullList()
        } else {
            Toast.makeText(requireContext(), "Permission not granted", Toast.LENGTH_SHORT).show()
            requireActivity().finish()
        }
    }

    private fun requestForStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                storageActivityResultLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storageActivityResultLauncher.launch(intent)
            }
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    private val storageActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission granted
                initRecyclerView()
                updateAdapterWithFullList()
            } else {
                // Permission denied
                Toast.makeText(requireContext(), "Permission not granted", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        } else {
            if (result.resultCode == Activity.RESULT_OK) {
                // Permission granted
                initRecyclerView()
                updateAdapterWithFullList()
            } else {
                // Permission denied
                Toast.makeText(requireContext(), "Permission not granted", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        }
    }

    private fun initRecyclerView() {
        // Initialize RecyclerView and adapter as before
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.isNestedScrollingEnabled = false
        adapter = FileAdapter(requireContext(), this, ArrayList())
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        binding.recyclerView.adapter = adapter
        // Update the adapter with the initial file list
        updateAdapterWithFullList()
    }

    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //Android is 11 (R) or above
            Environment.isExternalStorageManager()
        } else {
            //Below android 11
            val write =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun startFileObserver() {
        if (!isObserving) {
            val directoryToObserve = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fileObserver = object : FileObserver(directoryToObserve, CREATE or DELETE or MOVE_SELF) {
                    override fun onEvent(event: Int, path: String?) {
                        // Handle file change event
                        if (event and (CREATE or DELETE) != 0 && path != null) {
                            val fullPath = "$directoryToObserve/$path"
                            val file = File(fullPath)

                            // Check if the path corresponds to a directory
                            if (file.isDirectory || event and CREATE != 0) {
                                // Update the adapter with the updated file list
                                updateAdapterWithFullList()
                            }
                        }
                    }
                }
            } else {
                fileObserver = object : FileObserver(directoryToObserve.absolutePath, CREATE or DELETE or MOVED_TO) {
                    override fun onEvent(event: Int, path: String?) {
                        // Handle file change event
                        if (event and (CREATE or DELETE) != 0 && path != null) {
                            val fullPath = "$directoryToObserve/$path"
                            val file = File(fullPath)

                            // Check if the path corresponds to a directory
                            if (file.isDirectory || event and CREATE != 0) {
                                // Update the adapter with the updated file list
                                updateAdapterWithFullList()
                            }
                        }
                    }
                }
            }

            // Start the file observer
            fileObserver?.startWatching()
            isObserving = true

            // Start continuous update job
            fileUpdateJob = CoroutineScope(Dispatchers.Main).launch {
                while (isObserving) {
                    updateAdapterWithFullList()
                    delay(1000) // Update every second
                }
            }
        }
    }

    private fun stopFileObserver() {
        // Stop the file observer when the activity is destroyed
        fileObserver?.stopWatching()
        isObserving = false

        // Cancel the continuous update job
        fileUpdateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()

        stopFileObserver()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(extractionReceiver)
    }

    @SuppressLint("InflateParams")
    private fun showCompressorArchiveDialog(file: String) {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_compressor_archive, null)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(view)

        val btnLzma = view.findViewById<MaterialButton>(R.id.btnLzma)
        val btnBzip2 = view.findViewById<MaterialButton>(R.id.btnBzip2)
        val btnXz = view.findViewById<MaterialButton>(R.id.btnXz)
        val btnGzip = view.findViewById<MaterialButton>(R.id.btnGzip)
        val extractBtn = view.findViewById<MaterialButton>(R.id.btnExtract)
        val btnOpenWith = view.findViewById<MaterialButton>(R.id.btnOpenWith)
        val fileNameTv = view.findViewById<TextView>(R.id.fileName)

        fileNameTv.text = File(file).name

        btnLzma.setOnClickListener {
            startCompressService(file, CompressorStreamFactory.LZMA)
            bottomSheetDialog.dismiss()
        }

        btnBzip2.setOnClickListener {
            startCompressService(file, CompressorStreamFactory.BZIP2)
            bottomSheetDialog.dismiss()
        }

        btnXz.setOnClickListener {
            startCompressService(file, CompressorStreamFactory.XZ)
            bottomSheetDialog.dismiss()
        }

        btnGzip.setOnClickListener {
            startCompressService(file, CompressorStreamFactory.GZIP)
            bottomSheetDialog.dismiss()
        }

        extractBtn.setOnClickListener {
            startExtractionService(file, null)
            bottomSheetDialog.dismiss()
        }

        btnOpenWith.setOnClickListener {
            val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", File(file))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(File(file)))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun startCompressService(file: String, compressionFormat: String) {
        aProgressDialog.show()
        val intent = Intent(requireContext(), CompressCsArchiveService::class.java).apply {
            putExtra(CompressCsArchiveService.EXTRA_FILE_PATH, file)
            putExtra(CompressCsArchiveService.EXTRA_COMPRESSION_FORMAT, compressionFormat)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun startArchiveTarService(file: List<String>, archiveName: String) {
        aProgressDialog.show()
        val intent = Intent(requireContext(), ArchiveTarService::class.java).apply {
            putStringArrayListExtra(ArchiveTarService.EXTRA_FILES_TO_ARCHIVE, ArrayList(file))
            putExtra(ArchiveTarService.EXTRA_ARCHIVE_NAME, archiveName)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    @SuppressLint("InflateParams")
    private fun showBottomSheetOptions(filePaths: String, file: File) {
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_option, null)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(bottomSheetView)

        val btnExtract = bottomSheetView.findViewById<Button>(R.id.btnExtract)
        val btnMultiExtract = bottomSheetView.findViewById<Button>(R.id.btnMultiExtract)
        val btnMulti7zExtract = bottomSheetView.findViewById<Button>(R.id.btnMulti7zExtract)
        val btnFileInfo = bottomSheetView.findViewById<Button>(R.id.btnFileInfo)
        val btnMultiZipExtract = bottomSheetView.findViewById<Button>(R.id.btnMultiZipExtract)
        val btnOpenWith = bottomSheetView.findViewById<Button>(R.id.btnOpenWith)
        val fileNameTv = bottomSheetView.findViewById<TextView>(R.id.fileName)

        val filePath = file.absolutePath
        fileNameTv.text = file.name

        btnExtract.setOnClickListener {
            val fileExtension = file.name.split('.').takeLast(2).joinToString(".").lowercase()
            val supportedExtensions = listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz")

            if (supportedExtensions.any { fileExtension.endsWith(it) }) {
                startExtractionCsService(filePaths)
            } else {
                showPasswordInputDialog(filePaths)
            }
            bottomSheetDialog.dismiss()
        }

        btnMultiExtract.setOnClickListener {
            showPasswordInputMultiDialog(filePath)
            bottomSheetDialog.dismiss()
        }

        btnMulti7zExtract.setOnClickListener {
            showPasswordInputMulti7zDialog(filePath)
            bottomSheetDialog.dismiss()
        }

        btnMultiZipExtract.setOnClickListener {
            showPasswordInputMultiZipDialog(filePath)
            bottomSheetDialog.dismiss()
        }

        btnFileInfo.setOnClickListener {
            showFileInfo(file)
            bottomSheetDialog.dismiss()
        }

        btnOpenWith.setOnClickListener {
            val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun getMimeType(file: File): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun showPasswordInputDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMultiDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startRarExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startRarExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMulti7zDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startMulti7zExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMulti7zExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMultiZipDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startMultiZipExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMultiZipExtractionService(file, null)
            }
            .show()
    }

    private fun startExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
            putExtra(ExtractArchiveService.EXTRA_FILE_PATH, file)
            putExtra(ExtractArchiveService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startExtractionCsService(file: String) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractCsArchiveService::class.java).apply {
            putExtra(ExtractCsArchiveService.EXTRA_FILE_PATH, file)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startRarExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractRarService::class.java).apply {
            putExtra(ExtractRarService.EXTRA_FILE_PATH, file)
            putExtra(ExtractRarService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMulti7zExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractMultipart7zService::class.java).apply {
            putExtra(ExtractMultipart7zService.EXTRA_FILE_PATH, file)
            putExtra(ExtractMultipart7zService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMultiZipExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractMultipartZipService::class.java).apply {
            putExtra(ExtractMultipartZipService.EXTRA_FILE_PATH, file)
            putExtra(ExtractMultipartZipService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showFileInfo(file: File) {
        val fileName = file.name
        val filePath = file.absolutePath
        val fileSize = file.length()
        val lastModified = Date(file.lastModified())

        val fileInfo = """
            Name: $fileName
            Path: $filePath
            Size: ${fileSize / 1024} KB
            Last Modified: $lastModified
        """.trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("File Information")
            .setMessage(fileInfo)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun selectAllFiles() {
        for (i in 0 until adapter.itemCount) {
            if (!selectedFiles.contains(adapter.files[i])) {
                selectedFiles.add(adapter.files[i])
                adapter.toggleSelection(i)
            }
        }
        updateActionModeTitle()
    }

    fun startZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>) {
        aProgressDialog.show()
        val intent = Intent(requireContext(), ArchiveZipService::class.java).apply {
            putExtra(ArchiveZipService.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ArchiveZipService.EXTRA_PASSWORD, password)
            putExtra(ArchiveZipService.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ArchiveZipService.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ArchiveZipService.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ArchiveZipService.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ArchiveZipService.EXTRA_AES_STRENGTH, aesStrength)
            putStringArrayListExtra(ArchiveZipService.EXTRA_FILES_TO_ARCHIVE, ArrayList(filesToArchive))
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun startSplitZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, splitSize: Long?) {
        aProgressDialog.show()
        val intent = Intent(requireContext(), ArchiveSplitZipService::class.java).apply {
            putExtra(ArchiveSplitZipService.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ArchiveSplitZipService.EXTRA_PASSWORD, password)
            putExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ArchiveSplitZipService.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ArchiveSplitZipService.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ArchiveSplitZipService.EXTRA_AES_STRENGTH, aesStrength)
            putStringArrayListExtra(ArchiveSplitZipService.EXTRA_FILES_TO_ARCHIVE, ArrayList(filesToArchive))
            putExtra(ArchiveSplitZipService.EXTRA_SPLIT_SIZE, splitSize)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun convertToBytes(size: Long?, unit: String): Long? {
        return size?.times(when (unit) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            "GB" -> 1024L * 1024 * 1024
            else -> 1024L
        })
    }

    fun startSevenZService(password: String?, archiveName: String, compressionLevel: Int, solid: Boolean, threadCount: Int, filesToArchive: List<String>) {
        aProgressDialog.show()
        val intent = Intent(requireContext(), Archive7zService::class.java).apply {
            putExtra(Archive7zService.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(Archive7zService.EXTRA_PASSWORD, password)
            putExtra(Archive7zService.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(Archive7zService.EXTRA_SOLID, solid)
            putExtra(Archive7zService.EXTRA_THREAD_COUNT, threadCount)
            putStringArrayListExtra(Archive7zService.EXTRA_FILES_TO_ARCHIVE, ArrayList(filesToArchive))
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private suspend fun getFiles(): ArrayList<File> = withContext(Dispatchers.IO) {
        val files = ArrayList<File>()
        val directories = ArrayList<File>()
        val directory = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

        val fileList = directory.listFiles()

        if (fileList != null) {
            for (file in fileList) {
                if (file.isDirectory) {
                    directories.add(file)
                } else {
                    files.add(file)
                }
            }
        }

        when (sortBy) {
            SortBy.SORT_BY_NAME -> {
                directories.sortBy { it.name }
                files.sortBy { it.name }
            }
            SortBy.SORT_BY_SIZE -> files.sortBy { it.length() }
            SortBy.SORT_BY_MODIFIED -> files.sortBy { getFileTimeOfCreation(it) }
            SortBy.SORT_BY_EXTENSION -> files.sortBy { it.extension }
        }

        if (!sortAscending) {
            directories.reverse()
            files.reverse()
        }

        val combinedList = ArrayList<File>()
        combinedList.addAll(directories)
        combinedList.addAll(files)

        combinedList
    }

    private fun getFileTimeOfCreation(file: File): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            attr.lastModifiedTime().toMillis()
        } else {
            file.lastModified()
        }
    }

    private fun updateAdapterWithFullList() {
        if (!isSearchActive) {
            CoroutineScope(Dispatchers.Main).launch {
                val fullFileList = getFiles()
                adapter.updateFilesAndFilter(fullFileList)
            }
        }
    }

    private fun searchFiles(query: String?) {
        isSearchActive = !query.isNullOrEmpty()
        binding.circularProgressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val result = query?.let { searchAllFiles(File(Environment.getExternalStorageDirectory().absolutePath), it) } ?: emptyList()

            withContext(Dispatchers.Main) {
                adapter.updateFilesAndFilter(ArrayList(result))
                binding.circularProgressBar.visibility = View.GONE
            }
        }
    }

    private fun searchAllFiles(directory: File, query: String): List<File> {
        val result = mutableListOf<File>()
        val files = directory.listFiles() ?: return result

        for (file in files) {
            if (file.isDirectory) {
                result.addAll(searchAllFiles(file, query))
            } else if (file.name.contains(query, true)) {
                result.add(file)
            }
        }
        return result
    }
}
