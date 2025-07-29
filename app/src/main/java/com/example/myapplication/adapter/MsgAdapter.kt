package com.example.myapplication.adapter

import android.icu.text.SimpleDateFormat
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

/**
 * @param onRead  읽음 처리 후(점 숨김 + 뱃지 업데이트용) 콜백
 * @param dao     Room DAO
 * @param ioScope 외부 CoroutineScope (예: lifecycleScope) – 내부에서 IO 디스패처로 전환
 */
class MsgAdapter(
    private var data: List<UiMessage>,
    private val onRead: () -> Unit,
    private val dao: MessageDao,
    private val ioScope: CoroutineScope
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
            // ✅ 썸네일 파일이 실제로 존재할 경우에만 사용
            val iconFile = msg.iconPath?.takeIf { it.isNotBlank() && File(it).exists() }?.let { File(it) }
            Glide.with(itemView)
                .load(iconFile ?: R.drawable.ic_default)
                .placeholder(R.drawable.ic_default)
                .error(R.drawable.ic_default)
                .into(ivThumb)

            // ✅ 텍스트 구성
            tvTitle.text = msg.title
            tvBody.text  = "[${fmt.format(Date(msg.ts))}] ${msg.body}"

            // ✅ 읽음 표시
            dot.visibility = if (msg.read) View.GONE else View.VISIBLE

            // ✅ 클릭 시 읽음 처리
            itemView.setOnClickListener {
                if (!msg.read) {
                    msg.read = true
                    dot.visibility = View.GONE
                    notifyItemChanged(bindingAdapterPosition)

                    ioScope.launch(Dispatchers.IO) {
                        dao.markRead(msg.id)
                    }

                    onRead()
                }
            }
        }
    }
}
