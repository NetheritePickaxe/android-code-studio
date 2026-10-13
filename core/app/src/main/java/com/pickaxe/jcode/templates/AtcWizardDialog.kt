package com.pickaxe.jcode.templates

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment as AndroidEnvironment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.provider.DocumentsContractCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.pickaxe.jcode.atc.project.manager.builder.LanguageType
import com.pickaxe.jcode.R
import com.pickaxe.jcode.activities.FolderPickerActivity
import com.pickaxe.jcode.databinding.DialogAtcWizardBinding
import com.pickaxe.jcode.templates.android.Template
import com.pickaxe.jcode.templates.android.TemplateOptions
import com.pickaxe.jcode.templates.java.TemplateRegistry
import com.pickaxe.jcode.templates.preferences.Options
import com.pickaxe.jcode.templates.preferences.WizardPreferences
import com.pickaxe.jcode.utils.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AtcWizardDialog : BottomSheetDialogFragment() {

  private var listener: AtcInterface.TemplateCreationListener? = null
  private var selectedTemplate: Template? = null
  private var _binding: DialogAtcWizardBinding? = null
  private val binding
    get() = _binding!!

  fun init(listener: AtcInterface.TemplateCreationListener?) {
    this.listener = listener
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = BottomSheetDialog(requireContext(), theme)
    val ctx = requireContext()

    _binding =
        DataBindingUtil.inflate(LayoutInflater.from(ctx), R.layout.dialog_atc_wizard, null, false)

    setupSwitches()
    setupInputs(ctx)
    setupTemplatesGrid(ctx)
    setupButtons(ctx)

    dialog.setContentView(binding.root)
    return dialog
  }

  private fun setupSwitches() {
    with(binding) {
      // jcode targets plain Java/Kotlin Gradle projects; the CMake and NDK
      // controls are Android-only and are hidden here.
      useCMakeSwitch.visibility = View.GONE
      useKtsSwitch.isChecked = Options.OPT_USE_GRADLE_KTS

      useKtsSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_USE_GRADLE_KTS = isChecked
      }

      ndkVersionButton.visibility = View.GONE
    }
  }

  private fun setupInputs(ctx: Context) {
    val lastSaveLocation = WizardPreferences.getLastSaveLocation(ctx)
    binding.saveLocationInput.setText(lastSaveLocation ?: Environment.PROJECTS_DIR.absolutePath)

    binding.projectNameInput.addTextChangedListener(
        SimpleTextWatcher {
          updatePackageNameFromProject(it)
          validateProjectName()
        }
    )

    binding.saveLocationInput.addTextChangedListener(SimpleTextWatcher { validateProjectName() })

    binding.saveLocationLayout.setEndIconOnClickListener {
      (activity as? FragmentActivity)?.let { act ->
        FolderPickerActivity.onFolderPicked = { uriStr ->
          val path = SafResolver.resolveToPath(ctx, uriStr)
          binding.saveLocationInput.setText(path)
          WizardPreferences.setLastSaveLocation(ctx, path)
        }
        act.startActivity(Intent(act, FolderPickerActivity::class.java))
      }
    }

    setupDropdowns(ctx)
  }

  private fun setupDropdowns(ctx: Context) {
    val languageItems = arrayOf(ctx.getString(R.string.java), ctx.getString(R.string.kotlin))
    binding.languageInput.apply {
      setSimpleItems(languageItems)
      setText(languageItems[0], false)
      setOnClickListener { showDropDown() }
    }

    // minSdk and native language are Android-only; hidden for jcode.
    binding.minSdkInput.visibility = View.GONE
    (binding.minSdkInput.parent as? android.view.View)?.visibility = View.GONE
    binding.nativeLanguageInputLayout.visibility = View.GONE
  }

  private fun setupTemplatesGrid(ctx: Context) {
    val templates = TemplateRegistry.getAllTemplates()
    binding.templatesGrid.layoutManager = GridLayoutManager(ctx, 2)
    binding.templatesGrid.adapter =
        TemplateAdapter(ctx, templates) { template ->
          selectedTemplate = template
          template.configureOptions()
          proceedToOptionsPage(ctx)
        }
  }

  private fun setupButtons(ctx: Context) {
    binding.backButton.setOnClickListener {
      binding.root.post {
        SheetTransitions.slide(
            binding.wizardContainer,
            binding.pageOptions,
            binding.pageTemplates,
            MaterialSharedAxis.X,
            false,
        )
        binding.backButton.visibility = View.GONE
        binding.createButton.visibility = View.GONE
      }
    }

    binding.createButton.setOnClickListener { createProject(ctx) }
  }

  private fun proceedToOptionsPage(ctx: Context) {
    binding.root.post {
      val templateName = "My${selectedTemplate?.displayName?.replace(" ", "")}" ?: "MyProject"
      val packageSuffix =
          "my${selectedTemplate?.displayName?.replace(" ", ".")?.lowercase()}" ?: "myproject"

      binding.projectNameInput.setText(templateName)
      binding.packageNameInput.setText("com.example.$packageSuffix")

      SheetTransitions.slide(
          binding.wizardContainer,
          binding.pageTemplates,
          binding.pageOptions,
          MaterialSharedAxis.X,
          true,
      )
      binding.backButton.visibility = View.VISIBLE
      binding.createButton.visibility = View.VISIBLE
    }
  }

  private fun createProject(ctx: Context) {
    val proj =
        binding.projectNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectedTemplate?.displayName?.replace(" ", "")
            ?: "MyProject"
    val pkg =
        binding.packageNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: "com.example.${selectedTemplate?.displayName?.replace(" ", ".")?.lowercase() ?: "myproject"}"

    val lang =
        if (binding.languageInput.text?.toString()?.lowercase()?.startsWith("java") == true)
            LanguageType.JAVA
        else LanguageType.KOTLIN

    val savePath =
        binding.saveLocationInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: Environment.PROJECTS_DIR.absolutePath
    val projectDir = File(savePath, proj)

    if (projectDir.exists()) {
      showAlert(
          "Project Already Exists",
          "A project named '$proj' already exists at this location.",
      )
      return
    }

    WizardPreferences.setLastSaveLocation(ctx, savePath)
    WizardPreferences.addRecentProject(ctx, projectDir.absolutePath)

    selectedTemplate?.let { t ->
      CoroutineScope(Dispatchers.Main).launch {
        try {
          t.create(
              ctx,
              listener,
              TemplateOptions(proj, pkg, lang, Options.OPT_USE_GRADLE_KTS, File(savePath)),
          )
        } catch (e: Exception) {
          listener?.onTemplateCreated(false, "Error: ${e.message}")
        }
      }
    } ?: listener?.onCreationCancelled()

    dismiss()
  }

  private fun validateProjectName() {
    val projectName = binding.projectNameInput.text?.toString()?.trim().orEmpty()
    val saveLocation = binding.saveLocationInput.text?.toString()?.trim().orEmpty()

    if (projectName.isEmpty()) {
      binding.projectNameLayout.error = null
      return
    }

    val projectDir = File(saveLocation, projectName)

    when {
      projectDir.exists() -> {
        binding.projectNameLayout.error = "A project with this name already exists at this location"
        binding.createButton.isEnabled = false
      }
      !projectName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$")) -> {
        binding.projectNameLayout.error =
            "Project name must start with a letter and contain only letters, numbers, and underscores"
        binding.createButton.isEnabled = false
      }
      else -> {
        binding.projectNameLayout.error = null
        binding.createButton.isEnabled = true
      }
    }
  }

  private fun updatePackageNameFromProject(projectName: CharSequence?) {
    val current = binding.packageNameInput.text?.toString()?.trim().orEmpty()
    if (current.isNotEmpty() && current.contains('.')) {
      val segs = current.split('.').toMutableList()
      segs[segs.lastIndex] =
          projectName
              ?.toString()
              ?.trim()
              ?.lowercase()
              ?.replace("[^a-zA-Z0-9_]".toRegex(), "")
              ?.ifEmpty { "app" } ?: "app"
      binding.packageNameInput.setText(segs.joinToString("."))
    }
  }

  private fun showAlert(
      title: String,
      message: String,
      positiveText: String = "OK",
      onPositive: (() -> Unit)? = null,
  ) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
        .setNegativeButton("Cancel", null)
        .show()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}

class TemplateAdapter(
    private val ctx: Context,
    private val templates: List<Template>,
    private val onTemplateClick: (Template) -> Unit,
) : RecyclerView.Adapter<TemplateVH>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateVH {
    val displayMetrics = ctx.resources.displayMetrics
    val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
    android.util.Log.d("TemplateAdapter", "Screen Width DP: $screenWidthDp")
    
    val card =
        MaterialCardView(ctx).apply {
          layoutParams =
              ViewGroup.MarginLayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                  )
                  .apply { setMargins(8.dp, 8.dp, 8.dp, 8.dp) }
          radius = 20.dp.toFloat()
          isClickable = true
          isFocusable = true
          strokeWidth = 0
          elevation = 1.dp.toFloat()
        }

    val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val title =
        TextView(ctx).apply {
          textSize = 14f
          setPadding(12.dp, 12.dp, 12.dp, 8.dp)
          gravity = android.view.Gravity.CENTER
        }
    
    val image =
        ImageView(ctx).apply {
          scaleType = ImageView.ScaleType.CENTER_CROP
          
          if (screenWidthDp >= 600) {
            android.util.Log.d("TemplateAdapter", "Using small size for large screen")
            layoutParams = LinearLayout.LayoutParams(100.dp, 100.dp).apply {
              gravity = android.view.Gravity.CENTER
            }
          } else {
            android.util.Log.d("TemplateAdapter", "Using normal size")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160.dp)
          }
        }

    layout.addView(title)
    layout.addView(image)
    card.addView(layout)
    return TemplateVH(card, title, image)
  }

  override fun onBindViewHolder(holder: TemplateVH, position: Int) {
    val template = templates[position]
    holder.title.text = template.displayName

    val resId =
        ctx.resources.getIdentifier(
            template.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(),
            "drawable",
            ctx.packageName,
        )
    holder.image.setImageResource(if (resId != 0) resId else android.R.drawable.ic_menu_gallery)
    holder.card.setOnClickListener { onTemplateClick(template) }
  }

  override fun getItemCount() = templates.size
}

class TemplateVH(val card: MaterialCardView, val title: TextView, val image: ImageView) :
    RecyclerView.ViewHolder(card)

class SimpleTextWatcher(private val afterChanged: (CharSequence?) -> Unit) :
    android.text.TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

  override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

  override fun afterTextChanged(s: android.text.Editable?) = afterChanged(s)
}

private val Int.dp: Int
  get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

internal object SafResolver {
  private const val ANDROID_DOCS_AUTHORITY = "com.android.externalstorage.documents"
  private const val ANDROIDIDE_DOCS_AUTHORITY = "com.pickaxe.jcode.documents"

  fun resolveToPath(context: Context, uriStr: String): String {
    return try {
      val uri = Uri.parse(uriStr)
      val docUri =
          DocumentsContractCompat.buildDocumentUriUsingTree(
              uri,
              DocumentsContractCompat.getTreeDocumentId(uri)!!,
          ) ?: return Environment.PROJECTS_DIR.absolutePath

      val docId =
          DocumentsContractCompat.getDocumentId(docUri)
              ?: return Environment.PROJECTS_DIR.absolutePath

      when (docUri.authority) {
        ANDROIDIDE_DOCS_AUTHORITY -> docId
        ANDROID_DOCS_AUTHORITY -> {
          val split = docId.split(':')
          if (split.size != 2) return Environment.PROJECTS_DIR.absolutePath

          if (split[0] == "primary") {
            File(AndroidEnvironment.getExternalStorageDirectory(), split[1]).absolutePath
          } else {
            "/storage/${split[0]}/${split[1]}"
          }
        }
        else -> Environment.PROJECTS_DIR.absolutePath
      }
    } catch (e: Exception) {
      e.printStackTrace()
      Environment.PROJECTS_DIR.absolutePath
    }
  }
}
