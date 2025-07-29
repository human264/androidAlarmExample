package com.example.myapplication.adapter

import android.content.res.Resources
import android.graphics.Typeface
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.dto.UiCategory
import com.example.myapplication.dto.UiSubCategory
import java.io.File

class TreeAdapter(
    private val cats : MutableList<UiCategory>,
    private val subs : MutableList<UiSubCategory>,
    private val onSelect: (catId: String, subId: String?) -> Unit
) : RecyclerView.Adapter<TreeAdapter.VH>() {

    private val rows: List<Pair<Boolean, String>>
        get() = buildList {
            cats.forEach { c ->
                add(true to c.id)
                subs.filter { it.catId == c.id  }.forEach { add(false to it.id) }
            }
        }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv    = v.findViewById<ImageView>(R.id.ivIcon)
        val tv    = v.findViewById<TextView >(R.id.tvName)
        val badge = v.findViewById<TextView>(R.id.tvBadge)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
        LayoutInflater.from(p.context).inflate(R.layout.item_tree_row, p, false)
    )
    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (isCat, id) = rows[pos]

        if (isCat) {
            val c = cats.first { it.id == id }
            h.tv.apply { text = c.name; setTypeface(null, Typeface.BOLD) }
            h.badge.update(c.unreadCount)
            bindIcon(h.iv, c.iconPath)
            h.itemView.setOnClickListener { onSelect(c.id, null) }
        } else {
            val s = subs.first { it.id == id }
            h.tv.apply { text = s.name; setTypeface(null, Typeface.NORMAL) }
            h.badge.update(s.unreadCount)
            val icon = if (s.iconPath.isNotBlank()) s.iconPath
            else cats.first { it.id == s.catId }.iconPath
            bindIcon(h.iv, icon)
            (h.itemView.layoutParams as ViewGroup.MarginLayoutParams).marginStart = 24.dp
            h.itemView.setOnClickListener { onSelect(s.catId, s.id) }
        }
    }

    private fun bindIcon(iv: ImageView, path: String?) {
        val f = path?.let(::File)
        if (f == null || !f.exists()) { iv.setImageResource(R.drawable.ic_default); return }
        Glide.with(iv).load(f)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .signature(ObjectKey(f.lastModified()))
            .placeholder(R.drawable.ic_default)
            .error(R.drawable.ic_default).into(iv)
    }
}

private fun TextView.update(n: Int) {
    visibility = if (n > 0) View.VISIBLE else View.GONE
    text = n.toString()
}
val Int.dp get() = (this * Resources.getSystem().displayMetrics.density).toInt()
