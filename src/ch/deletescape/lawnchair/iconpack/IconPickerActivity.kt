package ch.deletescape.lawnchair.iconpack

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.os.Bundle
import android.os.Process
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import ch.deletescape.lawnchair.iconpack.EditIconActivity.Companion.EXTRA_ENTRY
import ch.deletescape.lawnchair.settings.ui.SettingsBaseActivity
import com.android.launcher3.R
import com.android.launcher3.compat.LauncherAppsCompat

class IconPickerActivity : SettingsBaseActivity(), View.OnLayoutChangeListener {

    private val iconPackManager = IconPackManager.getInstance(this)
    private val iconGrid by lazy { findViewById<RecyclerView>(R.id.iconGrid) }
    private val iconPack by lazy { iconPackManager.getIconPack(intent.getStringExtra(EXTRA_ICON_PACK), false) }
    private val items = ArrayList<AdapterItem>()
    private val adapter = IconGridAdapter()
    private val layoutManager = GridLayoutManager(this, 1)
    private var canceled = false

    private var dynamicPadding = 0

    private val pickerComponent by lazy { LauncherAppsCompat.getInstance(this)
            .getActivityList(iconPack.packPackageName, Process.myUserHandle()).firstOrNull()?.componentName }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_picker)

        title = iconPack.displayName

        getContentFrame().addOnLayoutChangeListener(this)

        supportActionBar?.run {
            setDisplayShowHomeEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }

        LoadIconTask().execute(iconPack)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (pickerComponent != null) menuInflater.inflate(R.menu.menu_icon_picker, menu)
        return super.onCreateOptionsMenu(menu) || pickerComponent != null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_open_external -> {
                val intent = Intent("com.novalauncher.THEME")
                        .addCategory("com.novalauncher.category.CUSTOM_ICON_PICKER")
                        .setComponent(pickerComponent)
                startActivityForResult(intent, 1000)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1000 && resultCode == Activity.RESULT_OK && data != null) {
            if (data.hasExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)) {
                val icon = data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
                val entry = (iconPack as IconPackImpl).createEntry(icon)
                onSelectIcon(entry)
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        canceled = true
    }

    override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        getContentFrame().removeOnLayoutChangeListener(this)
        calculateDynamicGrid(iconGrid.width)
        iconGrid.adapter = adapter
        iconGrid.layoutManager = layoutManager
    }

    private fun calculateDynamicGrid(width: Int) {
        val iconPadding = resources.getDimensionPixelSize(R.dimen.icon_preview_padding)
        val iconSize = resources.getDimensionPixelSize(R.dimen.icon_preview_size)
        val iconSizeWithPadding = iconSize + iconPadding + iconPadding
        val maxWidth = width - iconPadding - iconPadding
        val columnCount = maxWidth / iconSizeWithPadding
        val usedWidth = iconSize * columnCount
        dynamicPadding = (width - usedWidth) / (columnCount + 1) / 2
        layoutManager.spanCount = columnCount
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = getItemSpan(position)
        }
        iconGrid.setPadding(dynamicPadding, iconPadding, dynamicPadding, iconPadding)
    }

    private fun getItemSpan(position: Int)
            = if (adapter.isItem(position)) 1 else layoutManager.spanCount

    fun onSelectIcon(entry: IconPack.Entry) {
        val customEntry = entry.toCustomEntry()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ENTRY, customEntry.toString()))
        finish()
    }

    inner class IconGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val loadingType = 0
        private val itemType = 1
        private val categoryType = 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                itemType -> IconHolder(layoutInflater.inflate(R.layout.icon_item, parent, false))
                categoryType -> CategoryHolder(layoutInflater.inflate(R.layout.icon_category, parent, false))
                else -> LoadingHolder(layoutInflater.inflate(R.layout.adapter_loading, parent, false))
            }
        }

        override fun getItemCount() = Math.max(items.size, 1)

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is IconHolder) {
                holder.bind(items[position] as IconItem)
            } else if (holder is CategoryHolder) {
                holder.bind(items[position] as CategoryItem)
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when {
                items.size == 0 -> loadingType
                items[position] is IconItem -> itemType
                items[position] is CategoryItem -> categoryType
                else -> loadingType
            }
        }

        fun isItem(position: Int) = getItemViewType(position) == itemType

        inner class IconHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {

            init {
                itemView.setOnClickListener(this)
                (itemView.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    leftMargin = dynamicPadding
                    rightMargin = dynamicPadding
                }
            }

            fun bind(cachedEntry: IconItem) {
                (itemView as ImageView).setImageDrawable(cachedEntry.drawable)
            }

            override fun onClick(v: View) {
                onSelectIcon((items[adapterPosition] as IconItem).entry)
            }
        }

        inner class CategoryHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

            private val title: TextView = itemView.findViewById(android.R.id.title)

            fun bind(category: CategoryItem) {
                title.text = category.title
            }
        }

        inner class LoadingHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    inner class LoadIconTask : AsyncTask<IconPack, Void, List<AdapterItem>>() {

        override fun doInBackground(vararg params: IconPack): List<AdapterItem> {
            val iconPack = params[0]
            val items = ArrayList<AdapterItem>()
            iconPack.getAllIcons().forEach {
                items.add(CategoryItem(it.title))
                it.icons.forEach {
                    items.add(IconItem(it, it.drawable))
                }
            }
            return items
        }

        override fun onPostExecute(result: List<AdapterItem>) {
            if (canceled) return
            items.addAll(result)
            adapter.notifyDataSetChanged()
        }
    }

    open class AdapterItem

    class CategoryItem(val title: String) : AdapterItem()

    class IconItem(val entry: IconPack.Entry, val drawable: Drawable) : AdapterItem()

    companion object {

        private const val EXTRA_ICON_PACK = "pack"

        fun newIntent(context: Context, packageName: String): Intent {
            return Intent(context, IconPickerActivity::class.java).apply {
                putExtra(EXTRA_ICON_PACK, packageName)
            }
        }
    }
}