package com.example.myapplication.adapter

import android.icu.text.SimpleDateFormat
import android.util.Log                              // ★ NEW
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.dto.UiMessage
import com.example.myapplication.database.MessageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.Locale

class MsgAdapter(
    private var data: List<UiMessage>,
    private val onRead: () -> Unit,
    private val dao: MessageDao,
    private val ioScope: CoroutineScope,
    private val syncRead: suspend () -> Unit
) : RecyclerView.Adapter<MsgAdapter.VH>() {

    fun submit(new: List<UiMessage>) {
        data = new
        notifyDataSetChanged()
    }

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_msg_row, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(data[position])

    inner class VH(item: View) : RecyclerView.ViewHolder(item) {

        private val ivThumb = item.findViewById<ImageView>(R.id.ivThumb)
        private val tvTitle = item.findViewById<TextView>(R.id.tvTitle)
        private val tvBody  = item.findViewById<TextView>(R.id.tvBody)
        private val dot     = item.findViewById<View>(R.id.vUnreadDot)
        private val fmt     = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun bind(msg: UiMessage) {
            /* ① 썸네일 */
            val iconFile = msg.iconPath
                ?.takeIf { it.isNotBlank() && File(it).exists() }
                ?.let { File(it) }
            Glide.with(itemView)
                .load(iconFile ?: R.drawable.ic_default)
                .placeholder(R.drawable.ic_default)
                .error(R.drawable.ic_default)
                .into(ivThumb)

            /* ② 텍스트 */
            tvTitle.text = msg.title
            tvBody.text  = "[${fmt.format(Date(msg.ts))}] ${msg.body}"

            /* ③ 읽음 점 표시 */
            dot.visibility = if (msg.read) View.GONE else View.VISIBLE

            /* ④ 클릭 → 읽음 처리 */
            itemView.setOnClickListener {
                if (!msg.read) {
                    /* 4‑1 화면 즉시 반영 */
                    msg.read   = true
                    msg.synced = false                 // ← UiMessage 에 필드가 있다면
                    dot.visibility = View.GONE
                    notifyItemChanged(bindingAdapterPosition)

                    /* 4‑2 DB 반영 + 로그 */
                    Log.d("MsgAdapter", "READ click id=${msg.id}")
                    ioScope.launch(Dispatchers.IO) {
                        dao.markReadList(listOf(msg.id))
                        syncRead()
                    }

                    /* 4‑3 상위 UI 업데이트 콜백 */
                    onRead()
                }
            }
        }
    }
}
