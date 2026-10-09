package com.company.udpxytv.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.company.udpxytv.R
import com.company.udpxytv.data.Channel
import com.company.udpxytv.data.ChannelStore
import com.company.udpxytv.data.StreamUrlBuilder
import com.company.udpxytv.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import android.view.LayoutInflater

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: ChannelStore
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = ChannelStore(this)
        store.seedPresetsIfNeeded()

        adapter = ChannelAdapter(
            onClick = { ch -> play(ch) },
            onLongClick = { ch -> showChannelMenu(ch) }
        )
        b.channelGrid.layoutManager = LinearLayoutManager(this)
        b.channelGrid.adapter = adapter

        b.fabAdd.setOnClickListener { showAddDialog(null) }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.channels()
        adapter.submit(list)
        b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.channelGrid.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 统一的轻提示：锚定在频道网格上（输入框下方），
     * 避开顶部输入框区域，且随 FAB 一起让位。
     */
    private fun toast(msg: String) {
        Snackbar.make(b.channelGrid, msg, Snackbar.LENGTH_SHORT).apply {
            setBackgroundTint(getColor(R.color.bg_hover))
            setTextColor(getColor(R.color.text_primary))
            view.translationY = 0f
        }.show()
    }

    private fun play(ch: Channel) {
        if (StreamUrlBuilder.normalizeGateway(store.gateway) == null) {
            toast("请先在设置里填写本局域网的 udpxy 网关地址和端口")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val url = StreamUrlBuilder.build(store.gateway, ch.multicast, store.udpPath)
        if (url == null) {
            toast("无法识别这个组播地址")
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_NAME, ch.name)
                .putExtra(PlayerActivity.EXTRA_URL, url)
        )
    }

    private fun showChannelMenu(ch: Channel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(ch.name)
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> showAddDialog(ch)
                    1 -> confirmDelete(ch)
                }
            }
            .show()
    }

    private fun confirmDelete(ch: Channel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除频道")
            .setMessage("确定删除「${ch.name}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.deleteChannel(ch.id)
                refresh()
            }
            .show()
    }

    private fun showAddDialog(existing: Channel?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_channel, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val etMulti = view.findViewById<TextInputEditText>(R.id.etMulticast)
        val tvErr = view.findViewById<android.widget.TextView>(R.id.tvError)

        existing?.let {
            etName.setText(it.name)
            etMulti.setText(it.multicast)
        }

        val title = if (existing == null) "添加频道" else "编辑频道"
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text?.toString()?.trim().orEmpty()
                val multi = etMulti.text?.toString()?.trim().orEmpty()

                if (name.isEmpty()) {
                    tvErr.text = "请填写频道名称"
                    tvErr.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (StreamUrlBuilder.normalizeMulticast(multi) == null) {
                    tvErr.text = "无法识别这个组播地址"
                    tvErr.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (existing == null && store.hasChannelWith(multi)) {
                    tvErr.text = "该组播地址已存在"
                    tvErr.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                if (existing == null) {
                    store.addChannel(name, multi)
                } else {
                    if (!store.updateChannel(existing.id, name, multi)) {
                        tvErr.text = "该组播地址已被其它频道占用"
                        tvErr.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                }
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }
}
