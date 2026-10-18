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
package com.pickaxe.jcode.fragments

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.pickaxe.jcode.viewmodel.GitViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.pickaxe.jcode.R
import com.pickaxe.jcode.databinding.FragmentSettingsBinding
import com.pickaxe.jcode.git.GitConfigHelper
import com.pickaxe.jcode.utils.PreferencesManager
import com.pickaxe.jcode.viewmodel.GitViewModel
import java.io.File
import java.io.FileOutputStream

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private val prefsManager: PreferencesManager by lazy { PreferencesManager(requireContext()) }
    private val viewModel: GitViewModel by activityViewModels { GitViewModelFactory(requireContext()) }

    private val sshKeyPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSshKey(uri)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        loadSettings()
        setupButtons()
    }
    
    private fun loadSettings() {
        binding.textUserName.text = prefsManager.getGitUserName()
        binding.textUserEmail.text = prefsManager.getGitUserEmail()
        binding.switchRememberCredentials.isChecked = prefsManager.shouldRememberCredentials()
        
        val hasCredentials = prefsManager.getUsername() != null
        binding.textCredentialsStatus.text = if (hasCredentials) "Saved" else "Not saved"
        
        binding.editMirrorUrl.setText(prefsManager.getGitHubMirror() ?: "")
        binding.editProxyHost.setText(prefsManager.getProxyHost() ?: "")
        val port = prefsManager.getProxyPort()
        binding.editProxyPort.setText(if (port > 0) port.toString() else "")

        updateSshKeyStatus()
    }

    private fun updateSshKeyStatus() {
        val keyPath = prefsManager.getSshKeyPath()
        val configured = !keyPath.isNullOrBlank() && File(keyPath).exists()
        binding.textSshKeyStatus.text =
            getString(if (configured) R.string.hl_git_ssh_key_set else R.string.hl_git_ssh_key_not_set)
    }
    
    private fun setupButtons() {
        binding.buttonEditUserConfig.setOnClickListener {
            showEditUserConfigDialog()
        }
        
        binding.switchRememberCredentials.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setRememberCredentials(isChecked)
            if (!isChecked) {
                prefsManager.clearCredentials()
                binding.textCredentialsStatus.text = "Not saved"
            }
        }
        
        binding.buttonClearCredentials.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Credentials")
                .setMessage("Are you sure you want to clear saved credentials?")
                .setPositiveButton("Clear") { _, _ ->
                    prefsManager.clearCredentials()
                    binding.textCredentialsStatus.text = "Not saved"
                    Snackbar.make(binding.root, "Credentials cleared", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        binding.buttonSaveNetwork.setOnClickListener {
            val mirror = binding.editMirrorUrl.text?.toString()?.trim() ?: ""
            val host = binding.editProxyHost.text?.toString()?.trim() ?: ""
            val portText = binding.editProxyPort.text?.toString()?.trim() ?: ""
            val port = portText.toIntOrNull() ?: 0
            
            prefsManager.setGitHubMirror(mirror)
            prefsManager.setProxyHost(host)
            prefsManager.setProxyPort(port)
            // Apply proxy immediately so it takes effect for subsequent operations.
            GitConfigHelper.configureProxy(prefsManager)
            
            Snackbar.make(binding.root, R.string.hl_network_settings_saved, Snackbar.LENGTH_SHORT).show()
        }
        
        binding.buttonSelectSshKey.setOnClickListener {
            sshKeyPicker.launch(arrayOf("*/*"))
        }
        
        binding.buttonClearSshKey.setOnClickListener {
            val keyPath = prefsManager.getSshKeyPath()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.hl_git_clear_ssh_key)
                .setMessage(R.string.hl_git_ssh_key_clear_confirm)
                .setPositiveButton(R.string.clear) { _, _ ->
                    if (!keyPath.isNullOrBlank()) {
                        File(keyPath).delete()
                    }
                    prefsManager.clearSshConfig()
                    updateSshKeyStatus()
                    Snackbar.make(binding.root, R.string.hl_git_ssh_key_cleared, Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
    
    private fun importSshKey(uri: Uri) {
        try {
            val dir = File(requireContext().filesDir, "git_ssh").apply { mkdirs() }
            val dest = File(dir, "id_key")
            val opened = requireContext().contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Unable to open selected file")
            opened.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            if (dest.length() == 0L) {
                dest.delete()
                Snackbar.make(binding.root, R.string.hl_git_ssh_key_import_failed, Snackbar.LENGTH_LONG).show()
                return
            }
            showPassphraseDialog(dir, dest)
        } catch (e: Exception) {
            e.printStackTrace()
            Snackbar.make(binding.root, R.string.hl_git_ssh_key_import_failed, Snackbar.LENGTH_LONG).show()
        }
    }
    
    private fun showPassphraseDialog(dir: File, keyFile: File) {
        val field = EditText(requireContext()).apply {
            hint = getString(R.string.hl_git_ssh_key_passphrase_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hl_git_select_ssh_private_key)
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                val passphrase = field.text?.toString() ?: ""
                val knownHosts = File(dir, "known_hosts").apply {
                    if (!exists()) createNewFile()
                }
                prefsManager.setSshKeyPath(keyFile.absolutePath)
                prefsManager.setSshKeyPassphrase(passphrase)
                prefsManager.setSshKnownHostsPath(knownHosts.absolutePath)
                updateSshKeyStatus()
                Snackbar.make(binding.root, R.string.hl_git_ssh_key_saved, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showEditUserConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_user_config, null)
        val editTextName = dialogView.findViewById<TextInputEditText>(R.id.editTextUserName)
        val editTextEmail = dialogView.findViewById<TextInputEditText>(R.id.editTextUserEmail)
        
        editTextName.setText(prefsManager.getGitUserName())
        editTextEmail.setText(prefsManager.getGitUserEmail())
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Git User Config")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = editTextName.text.toString().trim()
                val email = editTextEmail.text.toString().trim()
                
                if (name.isNotBlank() && email.isNotBlank()) {
                    prefsManager.setGitUserName(name)
                    prefsManager.setGitUserEmail(email)
                    viewModel.setUserConfig(name, email)
                    loadSettings()
                    Snackbar.make(binding.root, "User config updated", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, "Name and email cannot be empty", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}