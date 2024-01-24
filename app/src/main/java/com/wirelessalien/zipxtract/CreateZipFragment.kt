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

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wirelessalien.zipxtract.databinding.FragmentCreateZipBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutFeatureSetEncryptHeader
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile


class CreateZipFragment : Fragment(),  FileAdapter.OnDeleteClickListener, FileAdapter.OnFileClickListener {

    private lateinit var binding: FragmentCreateZipBinding
    private var outputDirectory: DocumentFile? = null
    private var pickedDirectory: DocumentFile? = null
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var prefs: SharedPreferences
    private val tempFiles = mutableListOf<File>()
    private var selectedFileUri: Uri? = null
    private val cachedFiles = mutableListOf<File>()
    private var cachedDirectoryName: String? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private lateinit var fileList: MutableList<File>
    private var cacheFile: File? = null


    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (result.data != null) {
                val clipData = result.data!!.clipData

                if (clipData != null) {
                    tempFiles.clear()

                    binding.circularProgressBar.visibility = View.VISIBLE

                    CoroutineScope(Dispatchers.IO).launch {
                        for (i in 0 until clipData.itemCount) {
                            val filesUri = clipData.getItemAt(i).uri

                            val cursor = requireActivity().contentResolver.query(filesUri, null, null, null, null)
                            if (cursor != null && cursor.moveToFirst()) {
                                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val displayName = cursor.getString(displayNameIndex)
                                val tempFile = File(requireContext().cacheDir, displayName)

                                // Copy the content from the selected file URI to a temporary file
                                requireActivity().contentResolver.openInputStream(filesUri)?.use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                tempFiles.add(tempFile)

                                // show picked files name
                                val selectedFilesText = getString(R.string.selected_files_text, tempFiles.size)
                                withContext(Dispatchers.Main) {
                                    binding.fileNameTextView.text = selectedFilesText
                                    binding.fileNameTextView.isSelected = true
                                }

                                cursor.close()
                            }
                        }

                        // Hide the progress bar on the main thread
                        withContext(Dispatchers.Main) {
                            binding.circularProgressBar.visibility = View.GONE
                            val fileList = getFilesInCacheDirectory(requireContext().cacheDir)
                            adapter.updateFileList(fileList)
                        }
                    }
                }
            }
        }
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedFileUri = result.data?.data
            if (selectedFileUri != null) {
                showToast(getString(R.string.file_picked_success))
                binding.createZipMBtn.isEnabled = true
                binding.circularProgressBar.visibility = View.VISIBLE

                // Display the file name from the intent
                val fileName = getZipFileName(selectedFileUri!!)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true

                // Copy the file to the cache directory in background

                CoroutineScope(Dispatchers.IO).launch {
                    cacheFile = File(requireContext().cacheDir, fileName)
                    cacheFile!!.outputStream().use { cache ->
                        requireContext().contentResolver.openInputStream(selectedFileUri!!)?.use { it.copyTo(cache) }
                    }

                    withContext(Dispatchers.Main) {
                        binding.circularProgressBar.visibility = View.GONE
                    }
                }

            } else {
                showToast(getString(R.string.file_picked_fail))
            }
        }
    }


    private val directoryFilesPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            requireActivity().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            pickedDirectory = DocumentFile.fromTreeUri(requireContext(), uri)

            copyFilesToCache(uri)

            val directoryName = pickedDirectory?.name
            binding.fileNameTextView.text = directoryName
            binding.fileNameTextView.isSelected = true
        }
    }

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            requireActivity().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputDirectory = DocumentFile.fromTreeUri(requireContext(), uri)

            val fullPath = outputDirectory?.uri?.path
            val displayedPath = fullPath?.replace("/tree/primary", "")

            if (displayedPath != null) {

                val directoryText = getString(R.string.directory_path, displayedPath)
                binding.directoryTextView.text = directoryText
            }

            // Save the output directory URI in SharedPreferences
            val editor = sharedPreferences.edit()
            editor.putString("outputDirectoryUriZip", uri.toString())
            editor.apply()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentCreateZipBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = requireActivity().getSharedPreferences("prefs", AppCompatActivity.MODE_PRIVATE)

        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


        fileList = getFilesInCacheDirectory(requireContext().cacheDir)

        binding.progressBar.visibility = View.GONE

        binding.circularProgressBar.visibility = View.GONE

        binding.progressBarNI.visibility = View.GONE

        binding.pickFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            pickFileLauncher.launch(intent)
        }

        binding.pickFilesButton.setOnClickListener {
            openFile()

            val cacheDir = requireContext().cacheDir
            if (cacheDir.isDirectory) {
                val children: Array<String> = cacheDir.list()!!
                for (i in children.indices) {
                    File(cacheDir, children[i]).deleteRecursively()
                }
            }
        }

        binding.changeDirectoryButton.setOnClickListener {
            chooseOutputDirectory()
        }

        binding.pickFolderButton.setOnClickListener {
            changeDirectoryFilesPicker()

            val cacheDir = requireContext().cacheDir
            if (cacheDir.isDirectory) {
                val children: Array<String> = cacheDir.list()!!
                for (i in children.indices) {
                    File(cacheDir, children[i]).deleteRecursively()
                }
            }
        }

        binding.zipSettingsBtn.setOnClickListener {
            showCompressionSettingsDialog()
        }

        binding.settingsInfo.setOnClickListener {
            //show alert dialog with info
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.settings_info_text))
                .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()

        }

        binding.clearCacheBtnDP.setOnClickListener {

            val editor = sharedPreferences.edit()
            editor.putString("outputDirectoryUriZip", null)
            editor.apply()

            // clear output directory
            outputDirectory = null
            binding.directoryTextView.text = getString(R.string.no_directory_selected)
            binding.directoryTextView.isSelected = false
            showToast(getString(R.string.output_directory_cleared))
        }

        binding.clearCacheBtnPF.setOnClickListener {

            //delete the complete cache directory folder, files and subdirectories
            val cacheDir = requireContext().cacheDir
            if (cacheDir.isDirectory) {
                val children: Array<String> = cacheDir.list()!!
                for (i in children.indices) {
                    File(cacheDir, children[i]).deleteRecursively()
                }
            }


            val fileList = getFilesInCacheDirectory(requireContext().cacheDir)
            adapter.updateFileList(fileList)

            selectedFileUri = null
            binding.fileNameTextView.text = getString(R.string.no_file_selected)
            binding.fileNameTextView.isSelected = false
            showToast(getString(R.string.selected_file_cleared))

        }

        binding.createZipMBtn.setOnClickListener {

            when {
                selectedFileUri != null -> {
                    createZip()
                }
                tempFiles.isNotEmpty() -> {
                    createZipFile()
                }
                cachedFiles.isNotEmpty() -> {
                    createZipFile()
                }
                else -> {
                    showToast(getString(R.string.file_picked_fail))
                }
            }

        }

        binding.create7zBtn.setOnClickListener {

            when {
                cacheFile != null -> {
                    create7zSingleFile()
                }
                tempFiles.isNotEmpty() -> {
                    create7zFile()
                }
                cachedFiles.isNotEmpty() -> {
                    create7zFile()
                }
                else -> {
                    showToast(getString(R.string.file_picked_fail))
                }
            }
        }

        if (requireActivity().intent?.action == Intent.ACTION_VIEW) {
            val uri = requireActivity().intent.data

            if (uri != null) {
                selectedFileUri = uri
                binding.createZipMBtn.isEnabled = true

                // Display the file name from the intent
                val fileName = getZipFileName(selectedFileUri)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true

            } else {
                showToast(getString(R.string.file_picked_fail))
            }
        }

        val savedDirectoryUri = sharedPreferences.getString("outputDirectoryUriZip", null)
        if (savedDirectoryUri != null) {
            outputDirectory = DocumentFile.fromTreeUri(requireContext(), Uri.parse(savedDirectoryUri))
            val fullPath = outputDirectory?.uri?.path

            val displayedPath = fullPath?.replace("/tree/primary:", "")

            if (displayedPath != null) {
                val directoryText = getString(R.string.directory_path, displayedPath)
                binding.directoryTextView.text = directoryText
            }
        } else {
            //do nothing
        }

        recyclerView = binding.recyclerViewFiles
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Replace getFilesInCacheDirectory with a function to get the initial list of files
        val fileList = getFilesInCacheDirectory(requireContext().cacheDir)

        adapter = FileAdapter(fileList, this, this)
        recyclerView.adapter = adapter

    }

    private fun getFilesInCacheDirectory(directory: File): MutableList<File> {
        val filesList = mutableListOf<File>()

        // Recursive function to traverse the directory
        fun traverseDirectory(file: File) {
            if (file.isDirectory) {
                file.listFiles()?.forEach {
                    traverseDirectory(it)
                }
            } else {
                filesList.add(file)
            }
        }

        traverseDirectory(directory)
        return filesList
    }

    override fun onDetach() {
        super.onDetach()

        val cacheDir = requireContext().cacheDir
        if (cacheDir.isDirectory) {
            val children: Array<String> = cacheDir.list()!!
            for (i in children.indices) {
                File(cacheDir, children[i]).deleteRecursively()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val fileList = getFilesInCacheDirectory(requireContext().cacheDir)
        adapter.updateFileList(fileList)
    }

    override fun onFileClick(file: File) {
        //open activity
        val intent = Intent(requireContext(), PickedFilesActivity::class.java)
        startActivity(intent)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onDeleteClick(file: File) {

        val cacheFile = File(requireContext().cacheDir, file.name)
        cacheFile.delete()

       // use relative path to delete the file from cache directory
        val relativePath = file.path.replace("${requireContext().cacheDir}/", "")
        val fileToDelete = File(requireContext().cacheDir, relativePath)
        fileToDelete.delete()
        val parentFolder = fileToDelete.parentFile
        if (parentFolder != null) {
            if (parentFolder.listFiles()?.isEmpty() == true) {
                parentFolder.delete()
            }
        }

        adapter.fileList.remove(file)
        adapter.notifyDataSetChanged()
    }

    private fun changeDirectoryFilesPicker() {

        directoryFilesPicker.launch(null)
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        pickFilesLauncher.launch(intent)


    }

    private fun chooseOutputDirectory() {
        directoryPicker.launch(null)
    }

    private fun copyFilesToCache(directoryUri: Uri) {

        binding.circularProgressBar.visibility = View.VISIBLE
        val contentResolver = requireContext().contentResolver

        // Create a DocumentFile from the directoryUri
        val directory = DocumentFile.fromTreeUri(requireContext(), directoryUri)

        // Save the directory name for later use
        cachedDirectoryName = directory?.name

        // Check if directory is null
        if (directory != null && cachedDirectoryName != null) {
            // Create a virtual directory in the cache
            val cachedDirectory = File(requireContext().cacheDir, cachedDirectoryName!!)
            cachedDirectory.mkdirs()

            // Get all files recursively
            val allFiles = mutableListOf<DocumentFile>()
            getAllFilesInDirectory(directory, allFiles)

            // Launch a coroutine in the IO dispatcher
            lifecycleScope.launch(Dispatchers.IO) {
                // Copy each file to the cache directory with the preserved folder structure
                for (file in allFiles) {
                    val relativePath = getRelativePath(directory, file)
                    val outputFile = File(cachedDirectory, relativePath)

                    // Ensure parent directories are created
                    outputFile.parentFile?.mkdirs()

                    val inputStream = contentResolver.openInputStream(file.uri)
                    val outputStream = FileOutputStream(outputFile)

                    inputStream?.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }

                    cachedFiles.add(outputFile)
                }

                // Switch to the Main dispatcher to update the UI
                withContext(Dispatchers.Main) {
                    val fileList = getFilesInCacheDirectory(requireContext().cacheDir)
                    adapter.updateFileList(fileList)
                    binding.circularProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun getRelativePath(baseDirectory: DocumentFile, file: DocumentFile): String {
        // Get the relative path of the file with respect to the base directory
        val basePath = baseDirectory.uri.path ?: ""
        val filePath = file.uri.path ?: ""
        return if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length)
        } else {
            // Handle the case where the file is not within the base directory
            file.name ?: ""
        }
    }

    private val cachedDirectory: File? get() {
        return if (cachedDirectoryName != null) {
            File(requireContext().cacheDir, cachedDirectoryName!!)
        } else {
            null
        }
    }

    private fun getAllFilesInDirectory(directory: DocumentFile?, fileList: MutableList<DocumentFile>) {
        if (directory != null && directory.isDirectory) {
            val files = directory.listFiles()
            for (file in files) {
                if (file.isFile) {
                    fileList.add(file)
                } else if (file.isDirectory) {
                    // Recursively get files in child directories
                    getAllFilesInDirectory(file, fileList)
                }
            }
        }
    }

    private fun getRelativePathForFile(baseDirectory: File, file: File): String {
        val basePath = baseDirectory.path
        val filePath = file.path
        return if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length)
        } else {
            file.name
        }
    }

    //Not so happy with this function
    //Sometimes it fails to create the 7z file
    //I will try to improve it in the future
    //But for now it works

    private fun create7zSingleFile() {

        binding.progressBarNI.visibility = View.VISIBLE
        showPasswordInputDialog7z { password, _, level, solid, thread ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val tempZipFile = File(requireContext().cacheDir, "temp_archive.7z")
                    val outputFileName = getZipFileName(selectedFileUri)

                    withContext(Dispatchers.IO) {
                        RandomAccessFile(tempZipFile, "rw").use { raf ->
                            val outArchive = SevenZip.openOutArchive7z()

                            outArchive.setLevel(level)
                            outArchive.setSolid(solid)
                            outArchive.setThreadCount(thread)
                            outArchive.setHeaderEncryption(true)

                            val fileToArchive = cacheFile!!

                            outArchive.createArchive(RandomAccessFileOutStream(raf), 1,
                                object : IOutCreateCallback<IOutItem7z>, ICryptoGetTextPassword,
                                    IOutFeatureSetEncryptHeader {
                                    override fun cryptoGetTextPassword(): String? {
                                        return password
                                    }

                                    override fun setOperationResult(operationResultOk: Boolean) {
                                        // Track each operation result here

                                    }

                                    override fun setTotal(total: Long) {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            binding.progressBarNI.max = total.toInt()
                                        }
                                    }

                                    override fun setCompleted(complete: Long) {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            binding.progressBarNI.progress = complete.toInt()
                                        }
                                    }

                                    override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItem7z>): IOutItem7z {
                                        val item = outItemFactory.createOutItem()

                                        if (fileToArchive.isDirectory) {
                                            // Directory
                                            item.propertyIsDir = true
                                        } else {
                                            // File
                                            item.dataSize = fileToArchive.length()
                                        }

                                        item.propertyPath = fileToArchive.name

                                        return item
                                    }

                                    override fun getStream(i: Int): RandomAccessFileInStream {

                                        return RandomAccessFileInStream(RandomAccessFile(fileToArchive, "r"))
                                    }

                                    override fun setHeaderEncryption(enabled: Boolean) {

                                        outArchive.setHeaderEncryption(enabled)
                                    }
                                })
                            outArchive.close()
                        }
                    }

                    if (outputDirectory != null && tempZipFile.exists()) {
                        val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                            outputDirectory!!.uri,
                            DocumentsContract.getTreeDocumentId(outputDirectory!!.uri))

                        val outputZipUri = DocumentsContract.createDocument(
                            requireActivity().contentResolver, outputUri, "application/x-7z-compressed", outputFileName.toString())

                        requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w").use { outputStream ->
                            FileInputStream(tempZipFile).use { tempInputStream ->
                                val buffer = ByteArray(1024)
                                var bytesRead: Int
                                while (tempInputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream!!.write(buffer, 0, bytesRead)
                                }
                            }
                        }

                        val cacheDir = requireContext().cacheDir
                        if (cacheDir.isDirectory) {
                            val children: Array<String> = cacheDir.list()!!
                            for (i in children.indices) {
                                File(cacheDir, children[i]).deleteRecursively()
                            }
                        }

                        // Notify the user about the success
                        withContext(Dispatchers.Main) {
                           showToast(getString(R.string.sevenz_creation_success))
                            binding.progressBarNI.visibility = View.GONE
                        }
                    }
                } catch (e: SevenZipException) {
                    e.printStackTraceExtended()
                    withContext(Dispatchers.Main) {
                        // Notify the user about the error
                       showToast(getString(R.string.sevenz_creation_failed))
                        binding.progressBarNI.visibility = View.GONE
                    }
                } catch (e: IOException) {
                    if (isNoStorageSpaceException(e)) {
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            showToast("No storage available")
                        }
                    } else {
                        showToast("${getString(R.string.extraction_failed)} ${e.message}")
                    }
                } catch (e: OutOfMemoryError) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        // Notify the user about the error
                        showToast("Out of memory")
                        binding.progressBarNI.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun create7zFile() {

        binding.progressBarNI.visibility = View.VISIBLE
        showPasswordInputDialog7z { password, archiveName, level, solid, thread ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val filesToArchive = tempFiles.ifEmpty { cachedFiles }
                    val tempZipFile = File(requireContext().cacheDir, "temp_archive.7z")
                    val outputFileName = "$archiveName.7z"
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(tempZipFile, "rw").use { raf ->
                            val outArchive = SevenZip.openOutArchive7z()

                            outArchive.setLevel(level)
                            outArchive.setSolid(solid)
                            outArchive.setThreadCount(thread)
                            outArchive.setHeaderEncryption(true)


                            outArchive.createArchive(RandomAccessFileOutStream(raf), filesToArchive.size,
                                object : IOutCreateCallback<IOutItem7z>, ICryptoGetTextPassword,
                                    IOutFeatureSetEncryptHeader {
                                    override fun cryptoGetTextPassword(): String? {
                                        return password
                                    }

                                    override fun setOperationResult(operationResultOk: Boolean) { }

                                    override fun setTotal(total: Long) {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            binding.progressBarNI.max = total.toInt()
                                        }
                                    }

                                    override fun setCompleted(complete: Long) {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            binding.progressBarNI.progress = complete.toInt()
                                        }
                                    }

                                    override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItem7z>): IOutItem7z {
                                        val item = outItemFactory.createOutItem()
                                        val file = filesToArchive[index]

                                        if (file.isDirectory) {
                                            // Directory
                                            item.propertyIsDir = true
                                        } else {
                                            // File
                                            item.dataSize = file.length()
                                        }

                                        item.propertyPath = file.name

                                        // Set the file structure inside the archive
                                        if (cachedFiles.contains(file)) {
                                            val relativePath = getRelativePathForFile(cachedDirectory!!, file)
                                            item.propertyPath = relativePath + (if (file.isDirectory) File.separator else "")
                                    } else {
                                            // Use the file name for tempFiles
                                            item.propertyPath = file.name
                                        }

                                        return item
                                    }

                                    override fun getStream(i: Int): ISequentialInStream {

                                        return RandomAccessFileInStream(RandomAccessFile(filesToArchive[i], "r"))
                                    }

                                    override fun setHeaderEncryption(enabled: Boolean) {

                                        outArchive.setHeaderEncryption(enabled)
                                    }
                                })

                            outArchive.close()
                        }
                    }

                    if (outputDirectory != null && tempZipFile.exists()) {
                        val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                            outputDirectory!!.uri,
                            DocumentsContract.getTreeDocumentId(outputDirectory!!.uri))

                        val outputZipUri = DocumentsContract.createDocument(
                            requireActivity().contentResolver, outputUri, "application/x-7z-compressed", outputFileName)

                        requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w").use { outputStream ->
                            FileInputStream(tempZipFile).use { tempInputStream ->
                                val buffer = ByteArray(1024)
                                var bytesRead: Int
                                while (tempInputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream!!.write(buffer, 0, bytesRead)
                                }
                            }
                        }

                        val cacheDir = requireContext().cacheDir
                        if (cacheDir.isDirectory) {
                            val children: Array<String> = cacheDir.list()!!
                            for (i in children.indices) {
                                File(cacheDir, children[i]).deleteRecursively()
                            }
                        }

                        // Notify the user about the success
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.sevenz_creation_success))
                            binding.progressBarNI.visibility = View.GONE
                        }
                    }
                } catch (e: SevenZipException) {
                    e.printStackTraceExtended()
                    withContext(Dispatchers.Main) {
                        // Notify the user about the error
                        showToast(getString(R.string.sevenz_creation_failed))
                        binding.progressBarNI.visibility = View.GONE
                    }
                } catch (e: IOException) {
                    if (isNoStorageSpaceException(e)) {
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            showToast("No storage available")
                        }
                    } else {
                        showToast("${getString(R.string.extraction_failed)} ${e.message}")
                    }
                } catch (e: OutOfMemoryError) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        // Notify the user about the error
                        showToast("Out of memory")
                        binding.progressBarNI.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun isNoStorageSpaceException(e: IOException): Boolean {
        return e.message?.contains("ENOSPC") == true || e.cause is ErrnoException && (e.cause as ErrnoException).errno == OsConstants.ENOSPC
    }

    private fun createZipFile() {

        val selectedCompressionMethod = getSavedCompressionMethod()
        val selectedCompressionLevel = getSavedCompressionLevel()
        val selectedEncryptionMethod = getSavedEncryptionMethod()
        val selectedEncryptionStrength = getSavedEncryptionStrength()

        val alertDialogBuilder = MaterialAlertDialogBuilder(requireContext())
        alertDialogBuilder.setTitle(getString(R.string.enter_password))

        // Set up the input for password
        val passwordInput = EditText(context)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordInput.hint = getString(R.string.password)

        // Set up the input for output file name
        val outputFileNameInput = EditText(context)
        outputFileNameInput.hint = getString(R.string.zip_without_zip)

        // Set up the layout for both inputs
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(passwordInput)
        layout.addView(outputFileNameInput)
        alertDialogBuilder.setView(layout)

        // Set up the buttons
        alertDialogBuilder.setPositiveButton(getString(R.string.encrypt)) { _, _ ->
            val password = passwordInput.text.toString()
            var outputFileName = outputFileNameInput.text.toString()

            if (outputFileName.isEmpty()) {
                // Use default name if empty
                outputFileName = getString(R.string.output_file_name)
            } else if (!outputFileName.endsWith(".zip", ignoreCase = true)) {
                // Automatically add .zip extension
                outputFileName += ".zip"
            }

            if (password.isNotEmpty()) {
                // User entered a password, proceed to create the password-protected zip
                CoroutineScope(Dispatchers.Main).launch {
                    showProgressBar(true)
                    createEncryptedZipMFiles(password, outputFileName, selectedCompressionMethod, selectedCompressionLevel, selectedEncryptionMethod, selectedEncryptionStrength)
                    showProgressBar(false)
                }
            } else {
                // Password is empty, proceed to create a non-encrypted zip
                CoroutineScope(Dispatchers.Main).launch {
                    showProgressBar(true)
                    createNonEncryptedZipMFiles(outputFileName, selectedCompressionMethod, selectedCompressionLevel)
                    showProgressBar(false)
                }
            }
        }

        alertDialogBuilder.setNegativeButton(getString(R.string.not_encrypted)) { _, _ ->
            var outputFileName = outputFileNameInput.text.toString()

            if (outputFileName.isEmpty()) {
                // Use default name if empty
                outputFileName = getString(R.string.output_file_name)
            } else if (!outputFileName.endsWith(".zip", ignoreCase = true)) {
                // Automatically add .zip extension
                outputFileName += ".zip"
            }

            // User clicked cancel, proceed to create a non-encrypted zip
            CoroutineScope(Dispatchers.Main).launch {
                showProgressBar(true)
                createNonEncryptedZipMFiles(outputFileName, selectedCompressionMethod, selectedCompressionLevel)
                showProgressBar(false)
            }
        }

        alertDialogBuilder.show()
    }

    private suspend fun createNonEncryptedZipMFiles(outputFileName: String, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel) {
        withContext(Dispatchers.IO) {

            val zipParameters = ZipParameters()
            zipParameters.compressionMethod = compressionMethod
            zipParameters.compressionLevel = compressionLevel
            zipParameters.isEncryptFiles = false
            zipParameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_192

            val tempZipFile = File.createTempFile("tempZip", ".zip")
            val zipFile = ZipFile(tempZipFile)
            val cachedDirectory = cachedDirectory

            try {
                when {
                    cachedDirectory != null -> {
                        zipFile.addFolder(cachedDirectory, zipParameters)
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            // Add the file to the zip
                            zipFile.addFile(tempFile, zipParameters)
                        }
                    }
                    else -> {
                        showToast(getString(R.string.please_select_files))
                    }
                }

                showToast(getString(R.string.zip_creation_success))
                showExtractionCompletedSnackbar(outputDirectory)

            } catch (e: ZipException) {
                showToast("${getString(R.string.zip_creation_failed)} ${e.message}")

            } finally {

                when {
                    cachedFiles.isNotEmpty() -> {
                        for (cachedFile in cachedFiles) {
                            cachedFile.delete()
                        }
                        cachedFiles.clear()
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            tempFile.delete()
                        }
                        tempFiles.clear()
                    }
                }
            }

            if (outputDirectory != null && tempZipFile.exists()) {
                val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                    outputDirectory!!.uri,
                    DocumentsContract.getTreeDocumentId(outputDirectory!!.uri))

                val outputZipUri = DocumentsContract.createDocument(
                    requireActivity().contentResolver, outputUri, "application/zip", outputFileName)

                requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w").use { outputStream ->
                    FileInputStream(tempZipFile).use { tempInputStream ->
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (tempInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream!!.write(buffer, 0, bytesRead)
                        }
                    }
                }

                showExtractionCompletedSnackbar(outputDirectory)
                showToast(getString(R.string.zip_creation_success))

            } else {
                showToast(getString(R.string.select_output_directory))
            }

            if (tempZipFile.exists())
                tempZipFile.delete()
        }
    }

    private suspend fun createEncryptedZipMFiles(password: String, outputFileName: String, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, encryptionMethod: EncryptionMethod, encryptionStrength: AesKeyStrength) {
        withContext(Dispatchers.IO) {

            val zipParameters = ZipParameters()
            zipParameters.compressionMethod = compressionMethod
            zipParameters.compressionLevel = compressionLevel
            zipParameters.isEncryptFiles = true
            zipParameters.encryptionMethod = encryptionMethod
            zipParameters.aesKeyStrength = encryptionStrength

            val tempZipFile = File.createTempFile("tempZip", ".zip")
            val zipFile = ZipFile(tempZipFile)
            val cachedDirectory = cachedDirectory

            try {
                // Set the password for the entire zip file
                zipFile.setPassword(password.toCharArray())

                when {
                    cachedDirectory != null -> {
                        zipFile.addFolder(cachedDirectory, zipParameters)
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            zipFile.addFile(tempFile, zipParameters)
                        }
                    }
                    else -> {
                        showToast(getString(R.string.files_select_request))
                    }
                }

                showExtractionCompletedSnackbar(outputDirectory)

            } catch (e: ZipException) {

                showToast("${getString(R.string.zip_creation_failed)} ${e.message}")

            } finally {

                when {
                    cachedFiles.isNotEmpty() -> {
                        for (cachedFile in cachedFiles) {
                            cachedFile.delete()
                        }
                        cachedFiles.clear()
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            tempFile.delete()
                        }
                        tempFiles.clear()
                    }
                }
            }

            if (outputDirectory != null && tempZipFile.exists()) {
                val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                    outputDirectory!!.uri,
                    DocumentsContract.getTreeDocumentId(outputDirectory!!.uri))

                val outputZipUri = DocumentsContract.createDocument(
                    requireActivity().contentResolver, outputUri, "application/zip", outputFileName)

                requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w").use { outputStream ->
                    FileInputStream(tempZipFile).use { tempInputStream ->
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (tempInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream!!.write(buffer, 0, bytesRead)
                        }
                    }
                }

            } else {
                showToast(getString(R.string.select_output_directory))
            }

            if (tempZipFile.exists())
                tempZipFile.delete()
        }
    }

    private suspend fun showProgressBar(show: Boolean) {
        withContext(Dispatchers.Main) {
            binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun createZip() {
        if (selectedFileUri != null) {
            val selectedCompressionMethod = getSavedCompressionMethod()
            val selectedCompressionLevel = getSavedCompressionLevel()
            val selectedEncryptionMethod = getSavedEncryptionMethod()
            val selectedEncryptionStrength = getSavedEncryptionStrength()
            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(getString(R.string.enter_password))
            val input = EditText(requireContext())
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            builder.setView(input)

            builder.setPositiveButton(getString(R.string.encrypt)) { _, _ ->
                val password = input.text.toString()
                if (password.isNotEmpty()) {
                    // Show a progress dialog or other UI indication here if desired
                    lifecycleScope.launch(Dispatchers.IO) {
                        // Perform the work in a background coroutine
                        requireActivity().contentResolver.openInputStream(selectedFileUri!!)?.use { inputStream ->
                            //progressbar visible
                            showProgressBar(true)
                            val zipFileName = getZipFileName(selectedFileUri)
                            if (zipFileName != null) {
                                val parameters = ZipParameters()
                                parameters.isEncryptFiles = true
                                parameters.encryptionMethod = selectedEncryptionMethod
                                parameters.compressionMethod = selectedCompressionMethod
                                parameters.compressionLevel = selectedCompressionLevel
                                parameters.fileNameInZip = getZipFileName(selectedFileUri)
                                parameters.aesKeyStrength = selectedEncryptionStrength

                                val tempZipFile = File.createTempFile("tempZipP", ".zip")

                                // Create a password-protected ZIP file using zip4j
                                val zipFile = ZipFile(tempZipFile)
                                zipFile.setPassword(password.toCharArray())
                                zipFile.addStream(inputStream, parameters)

                                if (outputDirectory != null) {
                                    val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                                        outputDirectory!!.uri,
                                        DocumentsContract.getTreeDocumentId(outputDirectory!!.uri)
                                    )
                                    val outputZipUri = DocumentsContract.createDocument(
                                        requireActivity().contentResolver, outputUri, "application/zip", zipFileName
                                    )

                                    requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w")
                                        .use { outputStream ->
                                            FileInputStream(tempZipFile).use { tempInputStream ->
                                                val buffer = ByteArray(1024)
                                                var bytesRead: Int
                                                while (tempInputStream.read(buffer)
                                                        .also { bytesRead = it } != -1
                                                ) {
                                                    outputStream!!.write(buffer, 0, bytesRead)
                                                }
                                            }
                                        }

                                    // Notify the MediaScanner about the new file
                                    MediaScannerConnection.scanFile(requireContext(), arrayOf(outputUri.path), null, null)
                                }

                                // Delete the temporary ZIP file
                                tempZipFile.delete()
                                showProgressBar(false)
                            }
                        }
                        showToast(getString(R.string.extraction_success))
                        showExtractionCompletedSnackbar(outputDirectory)
                        selectedFileUri = null

                    }
                } else {
                    showToast(getString(R.string.zip_creation_failed))
                    binding.progressBar.visibility = View.GONE

                }
            }

            builder.setNegativeButton(getString(R.string.not_encrypted)) { _, _ ->

                if (selectedFileUri != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        requireActivity().contentResolver.openInputStream(selectedFileUri!!)?.use { inputStream ->

                            showProgressBar(true)
                            val zipFileName = getZipFileName(selectedFileUri)
                            if (zipFileName != null) {
                                val parameters = ZipParameters()
                                parameters.isEncryptFiles = false
                                parameters.compressionMethod = selectedCompressionMethod
                                parameters.compressionLevel = selectedCompressionLevel
                                parameters.fileNameInZip = getZipFileName(selectedFileUri)

                                val tempZipFile = File.createTempFile("tempZipnP", ".zip")

                                // Create a password-protected ZIP file using zip4j
                                val zipFile = ZipFile(tempZipFile)
                                zipFile.addStream(inputStream, parameters)

                                if (outputDirectory != null) {
                                    val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                                        outputDirectory!!.uri,
                                        DocumentsContract.getTreeDocumentId(outputDirectory!!.uri)
                                    )
                                    val outputZipUri = DocumentsContract.createDocument(
                                        requireActivity().contentResolver, outputUri, "application/zip", zipFileName
                                    )

                                    requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w")
                                        .use { outputStream ->
                                            FileInputStream(tempZipFile).use { tempInputStream ->
                                                val buffer = ByteArray(1024)
                                                var bytesRead: Int
                                                while (tempInputStream.read(buffer)
                                                        .also { bytesRead = it } != -1
                                                ) {
                                                    outputStream!!.write(buffer, 0, bytesRead)
                                                }
                                            }
                                        }

                                    // Notify the MediaScanner about the new file
                                    MediaScannerConnection.scanFile(requireContext(), arrayOf(outputUri.path), null, null)
                                }

                                // Delete the temporary ZIP file
                                tempZipFile.delete()
                                showProgressBar(false)
                            }
                        }
                        showToast(getString(R.string.zip_creation_success))
                        showExtractionCompletedSnackbar(outputDirectory)
                        selectedFileUri = null
                    }
                }
                else {
                    showToast(getString(R.string.zip_creation_failed))
                    binding.progressBar.visibility = View.GONE
                }
            }

            builder.show()
        } else {
            showToast(getString(R.string.file_picked_fail))
        }
    }

    private suspend fun showExtractionCompletedSnackbar(outputDirectory: DocumentFile?) {
        withContext(Dispatchers.Main) {

            binding.progressBar.visibility = View.GONE

            // Show a snackbar with a button to open the ZIP file
            val snackbar = Snackbar.make(binding.root, getString(R.string.zip_creation_success), Snackbar.LENGTH_LONG)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                snackbar.setAction(getString(R.string.open_folder)) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setDataAndType(outputDirectory?.uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    startActivity(intent)
                }
            }

            snackbar.show()
        }
    }

    private fun getZipFileName(selectedFileUri: Uri?): String? {
        if (selectedFileUri != null) {
            val cursor = requireActivity().contentResolver.query(selectedFileUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        return it.getString(displayNameIndex)
                    }
                }
            }
        }
        return null
    }

    private fun showCompressionSettingsDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.zip_settings_dialog, null)
        builder.setView(view)

        val title = view.findViewById<TextView>(R.id.dialog_title)
        title.text = getString(R.string.compression_settings)

        val compressionMethodInput = view.findViewById<Spinner>(R.id.compression_method_input)
        val compressionLevelInput = view.findViewById<Spinner>(R.id.compression_level_input)
        val encryptionMethodInput = view.findViewById<Spinner>(R.id.encryption_method_input)
        val encryptionStrengthInput = view.findViewById<Spinner>(R.id.encryption_strength_input)

        val compressionMethods = CompressionMethod.values().map { it.name }
        val compressionMethodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, compressionMethods)
        compressionMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        compressionMethodInput.adapter = compressionMethodAdapter

        val savedCompressionMethod = getSavedCompressionMethod()
        val defaultCompressionMethodIndex = compressionMethods.indexOf(savedCompressionMethod.name)
        compressionMethodInput.setSelection(if (defaultCompressionMethodIndex != -1) defaultCompressionMethodIndex else 0)

        val compressionLevels = CompressionLevel.values().map { it.name }
        val compressionLevelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, compressionLevels)
        compressionLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        compressionLevelInput.adapter = compressionLevelAdapter

        val savedCompressionLevel = getSavedCompressionLevel()
        val defaultCompressionLevelIndex = compressionLevels.indexOf(savedCompressionLevel.name)
        compressionLevelInput.setSelection(if (defaultCompressionLevelIndex != -1) defaultCompressionLevelIndex else 2)

        val encryptionMethods = EncryptionMethod.values().map { it.name }
        val encryptionMethodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, encryptionMethods)
        encryptionMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        encryptionMethodInput.adapter = encryptionMethodAdapter

        val savedEncryptionMethod = getSavedEncryptionMethod()
        val defaultEncryptionMethodIndex = encryptionMethods.indexOf(savedEncryptionMethod.name)
        encryptionMethodInput.setSelection(if (defaultEncryptionMethodIndex != -1) defaultEncryptionMethodIndex else 0)

        val encryptionStrengths = AesKeyStrength.values().map { it.name }
        val encryptionStrengthAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, encryptionStrengths)
        encryptionStrengthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        encryptionStrengthInput.adapter = encryptionStrengthAdapter

        val savedEncryptionStrength = getSavedEncryptionStrength()
        val defaultEncryptionStrengthIndex = encryptionStrengths.indexOf(savedEncryptionStrength.name)
        encryptionStrengthInput.setSelection(if (defaultEncryptionStrengthIndex != -1) defaultEncryptionStrengthIndex else 0)

        builder.setPositiveButton(getString(R.string.save)) { _, _ ->
            val selectedCompressionMethod =
                CompressionMethod.valueOf(compressionMethods[compressionMethodInput.selectedItemPosition])
            val selectedCompressionLevel =
                CompressionLevel.valueOf(compressionLevels[compressionLevelInput.selectedItemPosition])
            val selectedEncryptionMethod =
                EncryptionMethod.valueOf(encryptionMethods[encryptionMethodInput.selectedItemPosition])
            val selectedEncryptionStrength =
                AesKeyStrength.valueOf(encryptionStrengths[encryptionStrengthInput.selectedItemPosition])
            saveCompressionMethod(selectedCompressionMethod)
            saveCompressionLevel(selectedCompressionLevel)
            saveEncryptionMethod(selectedEncryptionMethod)
            saveEncryptionStrength(selectedEncryptionStrength)
        }

        builder.show()
    }

    private fun saveCompressionMethod(compressionMethod: CompressionMethod) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPRESSION_METHOD, compressionMethod.name).apply()
    }

    private fun saveCompressionLevel(compressionLevel: CompressionLevel) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPRESSION_LEVEL, compressionLevel.name).apply()
    }

    private fun saveEncryptionMethod(encryptionMethod: EncryptionMethod) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ENCRYPTION_METHOD, encryptionMethod.name).apply()
    }

    private fun saveEncryptionStrength(encryptionStrength: AesKeyStrength) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ENCRYPTION_METHOD, encryptionStrength.name).apply()
    }

    private fun getSavedCompressionMethod(): CompressionMethod {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_COMPRESSION_METHOD, "DEFLATE") ?: "DEFLATE"
        return try {
            CompressionMethod.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            CompressionMethod.DEFLATE // Default value if the saved string is not a valid enum constant
        }
    }

    private fun getSavedCompressionLevel(): CompressionLevel {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_COMPRESSION_LEVEL, "NORMAL") ?: "NORMAL"
        return try {
            CompressionLevel.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            CompressionLevel.NORMAL // Default value if the saved string is not a valid enum constant
        }
    }

    private fun getSavedEncryptionMethod(): EncryptionMethod {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_ENCRYPTION_METHOD, "AES") ?: "AES"
        return try {
            EncryptionMethod.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            EncryptionMethod.AES // Default value if the saved string is not a valid enum constant
        }
    }

    private fun getSavedEncryptionStrength(): AesKeyStrength {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_ENCRYPTION_METHOD, "KEY_STRENGTH_256") ?: "KEY_STRENGTH_256"
        return try {
            AesKeyStrength.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            AesKeyStrength.KEY_STRENGTH_256 // Default value if the saved string is not a valid enum constant
        }
    }

    private fun showPasswordInputDialog7z(onPasswordEntered: (String?, String, Int, Boolean, Int) -> Unit) {
        val layoutInflater = LayoutInflater.from(requireContext())
        val customView = layoutInflater.inflate(R.layout.seven_z_option_dialog, null)

        val passwordEditText = customView.findViewById<EditText>(R.id.passwordEditText)
        val compressionSpinner = customView.findViewById<Spinner>(R.id.compressionSpinner)
        val solidCheckBox = customView.findViewById<CheckBox>(R.id.solidCheckBox)
        val threadCountEditText = customView.findViewById<EditText>(R.id.threadCountEditText)
        val archiveNameEditText = customView.findViewById<EditText>(R.id.archiveNameEditText)
        val filesToArchive = tempFiles.ifEmpty { cachedFiles }
        MaterialAlertDialogBuilder(requireContext())
            .setView(customView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->

                val defaultName = if (filesToArchive.isNotEmpty()) {
                    filesToArchive[0].nameWithoutExtension
                } else {
                    "archive"
                }
                val archiveName = archiveNameEditText.text.toString().ifBlank { defaultName }
                val password = passwordEditText.text.toString()
                val compressionLevel = when (compressionSpinner.selectedItemPosition) {
                    0 -> 0
                    1 -> 1
                    2 -> 3
                    3 -> 5
                    4 -> 7
                    5 -> 9
                    else -> -1
                }
                val solid = solidCheckBox.isChecked
                val threadCount = threadCountEditText.text.toString().toIntOrNull() ?: -1

                onPasswordEntered.invoke(
                    password.ifBlank { null },
                    archiveName,
                    compressionLevel,
                    solid,
                    threadCount

                )
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->

                val defaultName = if (filesToArchive.isNotEmpty()) {
                    filesToArchive[0].nameWithoutExtension
                } else {
                    "archive"
                }
                val archiveName = archiveNameEditText.text.toString().ifBlank { defaultName }
                val compressionLevel = when (compressionSpinner.selectedItemPosition) {
                    0 -> 0
                    1 -> 1
                    2 -> 3
                    3 -> 5
                    4 -> 7
                    5 -> 9
                    else -> -1
                }
                val solid = solidCheckBox.isChecked
                val threadCount = threadCountEditText.text.toString().toIntOrNull() ?: -1
                onPasswordEntered.invoke(
                    null,
                    archiveName,
                    compressionLevel,
                    solid,
                    threadCount
                )
            }
            .show()
    }

    private fun showToast(message: String) {
        // Show toast on the main thread
        requireActivity().runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREFS_NAME = "ZipPrefs"
        private const val KEY_COMPRESSION_METHOD = "compressionMethod"
        private const val KEY_COMPRESSION_LEVEL = "compressionLevel"
        private const val KEY_ENCRYPTION_METHOD = "encryptionMethod"
    }
}
