package com.mirad.reader.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.GroupEntity
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.shelf.*
import com.mirad.reader.ui.theme.MiradTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 只替换存储，菜单、筛选、批量动作和选择语义均调用生产实现。 */
@OptIn(InternalComposeUiApi::class)
class ShelfInteractionPreviewActivity : ComponentActivity() {
    private lateinit var fixture: ShelfInteractionFixture
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION || !::fixture.isInitialized) return
            val label = intent.getStringExtra("label").orEmpty()
            when (intent.getStringExtra("operation")) {
                "semantics-click" -> {
                    val target = when (intent.getStringExtra("target")) {
                        "paged" -> "左右翻页"
                        "scroll" -> "上下滚动"
                        "none" -> "无动画"
                        "slide" -> "滑动"
                        "cover" -> "覆盖"
                        else -> ""
                    }
                    val node = semanticNodes().firstOrNull {
                        target in labels(it) && !it.config.contains(SemanticsProperties.Disabled) &&
                            it.config.getOrNull(SemanticsActions.OnClick)?.action != null
                    }
                    fixture.semanticClickAccepted = node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
                    window.decorView.postDelayed({ writeState(label) }, 120)
                }
                "dump" -> writeState(label)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fixture = ShelfInteractionFixture(intent)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        registered = true
        setContent {
            MiradTheme {
                PageSurface("shelf-interaction-${fixture.scene}", bounded = true, animate = false) {
                    if (fixture.scene == "choices") ChoiceFixture(fixture) else ShelfFixture(fixture)
                }
            }
        }
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        registered = false
        super.onDestroy()
    }

    private fun semanticNodes(): List<SemanticsNode> = buildList {
        fun visit(view: View) {
            if (view is ViewRootForTest) addAll(view.semanticsOwner.getAllSemanticsNodes(true))
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(window.decorView)
    }

    private fun labels(node: SemanticsNode): List<String> =
        (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).distinct()

    private fun writeState(label: String) {
        val state = JSONObject().apply {
            put("label", label)
            put("scene", fixture.scene)
            put("layout", fixture.layout.name)
            put("selectedGroup", fixture.selectedGroup ?: JSONObject.NULL)
            put("selection", JSONArray(fixture.selection.toList()))
            put("visibleIds", JSONArray(fixture.visible().map { it.book.id }))
            put("groups", JSONArray(fixture.groups.map { it.id }))
            put("books", JSONArray(fixture.books.map { book -> JSONObject().apply {
                put("id", book.id); put("pinned", book.isPinned); put("hidden", book.isHidden)
                put("group", book.groupId ?: JSONObject.NULL)
            } }))
            put("confirmDelete", fixture.confirmDelete)
            put("openedBook", fixture.openedBook ?: JSONObject.NULL)
            put("pageMode", fixture.pageMode)
            put("pageAnim", fixture.pageAnim)
            put("disabledClicks", fixture.disabledClicks)
            put("semanticClickAccepted", fixture.semanticClickAccepted)
            put("semantics", JSONArray(semanticNodes().mapNotNull { node ->
                val names = labels(node)
                if (names.isEmpty()) null else JSONObject().apply {
                    put("labels", JSONArray(names))
                    put("selected", node.config.getOrNull(SemanticsProperties.Selected) ?: JSONObject.NULL)
                    put("enabled", !node.config.contains(SemanticsProperties.Disabled))
                    put("clickable", node.config.getOrNull(SemanticsActions.OnClick)?.action != null)
                    put("longClickable", node.config.getOrNull(SemanticsActions.OnLongClick)?.action != null)
                }
            }))
        }
        File(filesDir, "shelf-interaction-state.json").writeText(state.toString())
    }

    companion object { const val ACTION = "com.mirad.reader.preview.SHELF_INTERACTION_CHECK" }
}

private class ShelfInteractionFixture(intent: Intent) {
    val scene = intent.getStringExtra("scene") ?: "shelf"
    var layout by mutableStateOf(ShelfLayout.valueOf(intent.getStringExtra("layout") ?: "GRID"))
    var groups by mutableStateOf(buildList {
        add(GroupEntity(7, "测试分组", 0))
        if (intent.getIntExtra("groupCount", 1) > 1) add(GroupEntity(9, "另一分组", 1))
    })
    var books by mutableStateOf(buildList {
        add(BookEntity(1, "测试书甲", format = BookFormat.TXT, filePath = "memory-a.txt", groupId = 7, isPinned = true))
        add(BookEntity(2, "测试书乙", format = BookFormat.EPUB, filePath = "memory-b.epub", groupId = 7))
        if (intent.getBooleanExtra("hidden", false)) {
            add(BookEntity(3, "隐藏测试书", format = BookFormat.TXT, filePath = "memory-hidden.txt", isHidden = true))
        }
    })
    var selectedGroup by mutableStateOf<Long?>(if (intent.getBooleanExtra("selectGroup", false)) 7 else null)
    var selection by mutableStateOf(emptySet<Long>())
    var actionBook by mutableStateOf<BookEntity?>(null)
    var groupAction by mutableStateOf<GroupEntity?>(null)
    var groupDelete by mutableStateOf<GroupEntity?>(null)
    var showGroups by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    var singleDelete = false
    var openedBook: Long? = null
    var pageMode by mutableStateOf(PageMode.SCROLL)
    var pageAnim by mutableStateOf(PageAnim.COVER)
    var disabledClicks = 0
    var semanticClickAccepted = false

    fun shelf() = books.map { ShelfBook(it, null, 0f, 0L) }
    fun visible() = ShelfQuery.apply(shelf(), ShelfSort.RECENT, selectedGroup)
    fun setPinned(id: Long, pinned: Boolean) { books = books.map { if (it.id == id) it.copy(isPinned = pinned) else it } }
    fun deleteGroup(id: Long) {
        books = books.map { if (it.groupId == id) it.copy(groupId = null) else it }
        groups = groups.filterNot { it.id == id }
    }
    fun dismissDelete() {
        selection = ShelfGroupActions.dismissDeleteSelection(selection, singleDelete)
        singleDelete = false
        confirmDelete = false
    }
}

@Composable
private fun ShelfFixture(state: ShelfInteractionFixture) {
    val scope = rememberCoroutineScope()
    ShelfContent(
        ShelfContentState(shelf = state.shelf(), visible = state.visible(), groups = state.groups,
            layout = state.layout, selectedGroup = state.selectedGroup, selection = state.selection),
        ShelfContentActions(
            onOpenBook = { state.openedBook = it },
            onLayoutChange = { state.layout = it },
            onSelectGroup = { id, _ -> state.selectedGroup = id },
            onSelectionChange = { state.selection = it },
            onBookAction = { state.actionBook = it },
            onGroupAction = { state.groupAction = it },
            onManageGroups = { state.showGroups = true },
            onSelectAll = { state.selection = state.visible().map { it.book.id }.toSet() },
            onPinSelected = { scope.launch {
                ShelfGroupActions.commitPinSelected(state.selection.mapNotNull { id -> state.books.firstOrNull { it.id == id } }) { id, pinned ->
                    state.setPinned(id, pinned)
                }
                state.selection = emptySet()
            } },
            onDeleteSelected = { state.singleDelete = false; state.confirmDelete = true }
        )
    )
    state.actionBook?.let { book ->
        ActionSheet(book.title, onDismiss = { state.actionBook = null },
            items = ShelfGroupActions.bookActionItems(book,
                onOpen = { state.openedBook = book.id },
                onUnhide = {}, onEdit = {},
                onTogglePinned = { state.setPinned(book.id, !book.isPinned) },
                onMoveToGroup = {}, onHide = {}, onChapters = {}, onEncoding = {},
                onDelete = { state.selection = setOf(book.id); state.singleDelete = true; state.confirmDelete = true },
                onMultiSelect = { state.selection = setOf(book.id) }))
    }
    if (state.showGroups) GroupManagerContent(state.groups,
        state.groups.associate { group -> group.id to state.books.count { it.groupId == group.id } },
        onRename = { _, _ -> },
        onDelete = { group -> scope.launch {
            state.selectedGroup = ShelfGroupActions.commitDeleteGroup(group.id, { state.selectedGroup }) { state.deleteGroup(it) }
        } }, onDismiss = { state.showGroups = false })
    state.groupAction?.let { group ->
        ActionSheet(group.name, onDismiss = { state.groupAction = null },
            items = ShelfGroupActions.groupActionItems(group,
                onSelect = { state.selectedGroup = group.id }, onRename = {}, onDelete = { state.groupDelete = group }))
    }
    state.groupDelete?.let { group ->
        MiradDialog(onDismissRequest = { state.groupDelete = null },
            title = { Text("删除分组「${group.name}」") },
            text = { Text("组内书籍会回到「未分组」，书籍本身不会被删除。") },
            confirmButton = { MiradTextButton(danger = true, onClick = { scope.launch {
                state.selectedGroup = ShelfGroupActions.commitDeleteGroup(group.id, { state.selectedGroup }) { state.deleteGroup(it) }
                state.groupDelete = null
            } }) { Text("删除") } },
            dismissButton = { MiradTextButton(onClick = { state.groupDelete = null }) { Text("取消") } })
    }
    if (state.confirmDelete) MiradDialog(onDismissRequest = state::dismissDelete,
        title = { Text(if (state.selection.size == 1) "删除这本书" else "删除 ${state.selection.size} 本书籍") },
        text = { Text("将删除所选书籍及其阅读记录、书签与笔记，无法恢复。") },
        confirmButton = { MiradTextButton(danger = true, onClick = {
            state.books = state.books.filterNot { it.id in state.selection }
            state.selection = emptySet(); state.singleDelete = false; state.confirmDelete = false
        }) { Text("删除") } },
        dismissButton = { MiradTextButton(onClick = state::dismissDelete) { Text("取消") } })
}

@Composable
private fun ChoiceFixture(state: ShelfInteractionFixture) {
    MiradScaffold(topBar = { ScreenHeader("阅读设置") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SegmentedRow(listOf(PageMode.PAGED to "左右翻页", PageMode.SCROLL to "上下滚动"),
                state.pageMode, { state.pageMode = it })
            ChoiceRow("翻页动画", listOf(PageAnim.NONE to "无动画", PageAnim.SLIDE to "滑动", PageAnim.COVER to "覆盖"),
                state.pageAnim, { state.pageAnim = it }, columns = 3)
            ChoiceChip("不可用", selected = false, onClick = { state.disabledClicks++ }, enabled = false)
        }
    }
}
