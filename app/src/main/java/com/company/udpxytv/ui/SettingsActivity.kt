package com.company.udpxytv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.company.udpxytv.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.company.udpxytv.data.M3uExporter
import com.company.udpxytv.data.M3uImporter
import android.view.LayoutInflater
import com.company.udpxytv.data.ChannelStore
import com.company.udpxytv.data.StreamUrlBuilder
import com.company.udpxytv.databinding.ActivitySettingsBinding
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: ChannelStore

    /**
     * 导出走 SAF：让用户自己选保存位置，不需要任何存储权限。
     * 文件名先给出建议值，用户可在系统对话框里改。
     */
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri -> uri?.let { writeChannelsTo(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = ChannelStore(this)
        b.etGateway.setText(store.gateway)
        b.etUdpPath.setText(store.udpPath)
        updatePreview()

        b.btnBack.setOnClickListener { finish() }
        b.btnImport.setOnClickListener { showImportDialog() }
        b.btnExport.setOnClickListener { startExport() }
        b.rowWriteSettings.setOnClickListener { gotoWriteSettings() }
        refreshWriteSettingsState()
        b.etGateway.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updatePreview() }
        b.etUdpPath.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updatePreview() }

        b.btnSave.setOnClickListener {
            hideKeyboard(b.etGateway)
            b.etGateway.clearFocus()
            val gw = b.etGateway.text?.toString()?.trim().orEmpty()
            if (StreamUrlBuilder.normalizeGateway(gw) == null) {
                toast("无法识别这个地址", long = true)
                return@setOnClickListener
            }
            val p = b.etUdpPath.text?.toString()?.trim().orEmpty()
                .ifEmpty { ChannelStore.DEFAULT_UDP_PATH }
            store.gateway = gw
            store.udpPath = p
            toast("已保存")
            b.root.postDelayed({ finish() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWriteSettingsState()
    }

    private fun refreshWriteSettingsState() {
        val granted = Settings.System.canWrite(this)
        b.tvWriteSettings.text = if (granted) "已授予" else "未授予"
        b.tvWriteSettings.setTextColor(
            getColor(if (granted) R.color.brand_primary else R.color.text_secondary)
        )
    }

    private fun gotoWriteSettings() {
        if (Settings.System.canWrite(this)) {
            toast("已授予，无需再设置")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            toast("这台设备没有提供该授权入口", long = true)
        }
    }

    /** 统一轻提示，避开顶部输入框 */
    private fun toast(msg: String, long: Boolean = false) {
        Snackbar.make(
            b.root,
            msg,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        ).apply {
            setBackgroundTint(getColor(R.color.bg_hover))
            setTextColor(getColor(R.color.text_primary))
        }.show()
    }

    private fun hideKeyboard(view: android.view.View) {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun updatePreview() {
        val gw = b.etGateway.text?.toString()?.trim().orEmpty()
        val path = b.etUdpPath.text?.toString()?.trim().orEmpty()
            .ifEmpty { ChannelStore.DEFAULT_UDP_PATH }
        // 用中性示例拼一条，让用户直观看到最终播放地址的构成
        val sample = StreamUrlBuilder.build(gw, "233.0.0.1:1234", path)
        b.tvPreview.text = when {
            sample != null -> "最终播放地址\n$sample"
            gw.isBlank() -> "填写后这里会显示最终播放地址"
            else -> "还无法识别这个地址"
        }
    }

    private fun showImportDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_import, null)
        val etText = view.findViewById<android.widget.EditText>(R.id.etImport)
        val tvPreview = view.findViewById<android.widget.TextView>(R.id.tvPreview)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("批量导入频道")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .create()

        fun refreshImportPreview() {
            val text = etText.text?.toString().orEmpty()
            if (text.isBlank()) {
                tvPreview.text = "粘贴内容后这里会显示识别结果"
                return
            }
            val r = M3uImporter.parseWithReport(text, store.channels())
            tvPreview.text = when {
                r.imported.isEmpty() && r.skipped.isEmpty() -> "没识别出有效地址"
                r.imported.isEmpty() -> "没有可导入的地址，${r.skipped.size} 行无法识别"
                else -> buildString {
                    append("可导入 ${r.imported.size} 个，例如：")
                    append(r.imported.take(2).joinToString("、") { it.name })
                    if (r.skipped.isNotEmpty()) append("；${r.skipped.size} 行无法识别")
                }
            }
        }

        etText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshImportPreview() }
        })
        refreshImportPreview()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = etText.text?.toString().orEmpty()
                val result = M3uImporter.parseWithReport(text, store.channels())
                if (result.imported.isEmpty()) {
                    toast(
                        if (result.skipped.isEmpty()) "没识别出有效频道地址"
                        else "没有可导入的地址，${result.skipped.size} 行无法识别",
                        long = true
                    )
                    return@setOnClickListener
                }
                val added = store.addChannelsBulk(result.imported)
                dialog.dismiss()
                // 通知主界面刷新频道列表
                setResult(RESULT_OK)
                // 三类数量都报出来：成功的、与已有重复的、根本认不出来的。
                // 以前只报"导入 N 个"，多出来的行去哪了无从得知
                val parts = mutableListOf("已导入 $added 个")
                val dupSkipped = result.duplicated + (result.imported.size - added)
                if (dupSkipped > 0) parts.add("跳过 $dupSkipped 个重复")
                if (result.skipped.isNotEmpty()) parts.add("${result.skipped.size} 行无法识别")
                toast(parts.joinToString("，"), long = true)
            }
        }
        dialog.show()
    }

    /**
     * 触发导出。
     *
     * 先检查有没有频道——空列表导出只会得到一个只有头部的文件，
     * 与其让用户以为备份成功，不如直接说清楚。
     */
    private fun startExport() {
        val list = store.channels()
        if (list.isEmpty()) {
            toast("还没有频道可导出", long = true)
            return
        }
        runCatching {
            exportLauncher.launch(M3uExporter.suggestedFileName())
        }.onFailure {
            toast("这台设备没有提供保存入口", long = true)
        }
    }

    /** 把当前频道写成 m3u。SAF 返回的 uri 由系统保证可写。 */
    private fun writeChannelsTo(uri: Uri) {
        val list = store.channels()
        val text = M3uExporter.export(list)
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: throw IllegalStateException("打不开输出流")
        }.onSuccess {
            toast("已导出 " + list.size + " 个频道", long = true)
        }.onFailure {
            toast("导出失败：" + (it.message ?: "未知错误"), long = true)
        }
    }
}
