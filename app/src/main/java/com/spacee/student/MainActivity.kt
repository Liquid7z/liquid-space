@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.spacee.student

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

// -----------------------------------------------------------------------------
// Liquid Space — local-first student document manager
// -----------------------------------------------------------------------------

data class Subject(val id: Long, val name: String, val icon: String = "📚", val color: Long = 0xFF6C63FF)
data class Folder(val id: Long, val subjectId: Long, val name: String, val isPrivate: Boolean = false)
data class IncomingShare(val uris: List<Uri>, val displayName: String = "Shared file")
data class LocalDocument(
    val id: Long,
    var name: String,
    var path: String,
    var size: Long,
    var subjectId: Long,
    var category: String,
    var mimeType: String,
    var favorite: Boolean = false,
    var trashed: Boolean = false,
    var deletedAt: Long = 0L,
    var addedAt: Long = System.currentTimeMillis(),
    var lastOpenedAt: Long = 0L,
    var folderId: Long? = null,
    var checksum: String = ""
) {
    val isImage: Boolean get() = mimeType.startsWith("image/") || path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    val extension: String get() = name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
}

enum class ThemeChoice(val title: String, val emoji: String) {
    OBSIDIAN("Obsidian", "◼"), SNOW("Snow", "☀"), AURORA("Aurora", "🌌"), FOREST("Forest", "🌲"),
    SUNSET("Sunset", "🌅"), MIDNIGHT("Midnight Purple", "💜"), OCEAN("Ocean", "🌊"), ROSE("Rose", "🌹"),
    AMOLED("AMOLED", "●"), SYSTEM("System", "📱")
}

data class ThemePalette(val light: ColorScheme, val dark: ColorScheme)
enum class AppearanceMode(val title:String, val emoji:String){ LIGHT("Light","☀️"), DARK("Dark","🌙"), SYSTEM("Follow system","📱") }

data class LiquidThemeStyle(
    val cardRadius: Int,
    val controlRadius: Int,
    val tileRadius: Int,
    val cardElevation: Int,
    val borderAlpha: Float,
    val compact: Boolean,
    val label: String
)

private fun styleFor(choice: ThemeChoice)=when(choice){
    ThemeChoice.OBSIDIAN -> LiquidThemeStyle(22,18,18,0,.12f,false,"Soft dark")
    ThemeChoice.SNOW -> LiquidThemeStyle(16,14,14,1,.16f,true,"Clean")
    ThemeChoice.AURORA -> LiquidThemeStyle(28,22,24,2,.12f,false,"Glow")
    ThemeChoice.FOREST -> LiquidThemeStyle(20,16,18,1,.14f,false,"Natural")
    ThemeChoice.SUNSET -> LiquidThemeStyle(22,18,20,2,.14f,false,"Warm")
    ThemeChoice.MIDNIGHT -> LiquidThemeStyle(26,20,22,1,.10f,false,"Velvet")
    ThemeChoice.OCEAN -> LiquidThemeStyle(20,16,18,1,.16f,false,"Calm")
    ThemeChoice.ROSE -> LiquidThemeStyle(24,18,20,2,.14f,false,"Soft")
    ThemeChoice.AMOLED -> LiquidThemeStyle(12,10,12,0,.22f,true,"Pure black")
    ThemeChoice.SYSTEM -> LiquidThemeStyle(20,16,18,1,.14f,false,"System")
}

private fun paletteFor(choice: ThemeChoice): ThemePalette {
    fun light(primary: Long, secondary: Long, background: Long, surface: Long, surfaceVariant: Long) = lightColorScheme(
        primary = Color(primary), secondary = Color(secondary), background = Color(background), surface = Color(surface), surfaceVariant = Color(surfaceVariant)
    )
    fun dark(primary: Long, secondary: Long, background: Long, surface: Long, surfaceVariant: Long) = darkColorScheme(
        primary = Color(primary), secondary = Color(secondary), background = Color(background), surface = Color(surface), surfaceVariant = Color(surfaceVariant)
    )
    return when (choice) {
        ThemeChoice.OBSIDIAN -> ThemePalette(light(0xFF3949AB,0xFF5C6BC0,0xFFF7F7FA,0xFFFFFFFF,0xFFE9EAF2), dark(0xFFB9C1FF,0xFFB9C0D8,0xFF090A0E,0xFF12141A,0xFF20232D))
        ThemeChoice.SNOW -> ThemePalette(light(0xFF2457D6,0xFF536D9E,0xFFF9FAFC,0xFFFFFFFF,0xFFE9EDF5), dark(0xFF9FC0FF,0xFFB8C5DE,0xFF101318,0xFF181C23,0xFF242A35))
        ThemeChoice.AURORA -> ThemePalette(light(0xFF6C3EDB,0xFF0B9F8D,0xFFF8F7FC,0xFFFFFFFF,0xFFECE8F7), dark(0xFFC8A8FF,0xFF72E0D0,0xFF0D0B15,0xFF171221,0xFF2A2138))
        ThemeChoice.FOREST -> ThemePalette(light(0xFF2E7D52,0xFF607D68,0xFFF6FAF7,0xFFFFFFFF,0xFFE3ECE6), dark(0xFF82D6A7,0xFFA7CDB7,0xFF08110C,0xFF101A14,0xFF1F2A23))
        ThemeChoice.SUNSET -> ThemePalette(light(0xFFC85A28,0xFF875A45,0xFFFFF8F4,0xFFFFFFFF,0xFFF4E5DD), dark(0xFFFFB07D,0xFFE2B9A1,0xFF140C09,0xFF211410,0xFF35231D))
        ThemeChoice.MIDNIGHT -> ThemePalette(light(0xFF7047C8,0xFF756A9B,0xFFF9F7FF,0xFFFFFFFF,0xFFEAE4F7), dark(0xFFC9AEFF,0xFFB7A8D7,0xFF0C0912,0xFF17111F,0xFF2B2136))
        ThemeChoice.OCEAN -> ThemePalette(light(0xFF167EA6,0xFF4D7583,0xFFF4FAFC,0xFFFFFFFF,0xFFE0EDF1), dark(0xFF76D4F5,0xFFA5CAD7,0xFF071014,0xFF0E1B21,0xFF1B3038))
        ThemeChoice.ROSE -> ThemePalette(light(0xFFC34F79,0xFF805C6B,0xFFFFF7FA,0xFFFFFFFF,0xFFF3E3EA), dark(0xFFFFA8C8,0xFFE0B8C7,0xFF140A0F,0xFF21121A,0xFF35212B))
        ThemeChoice.AMOLED -> ThemePalette(light(0xFF111111,0xFF555555,0xFFFFFFFF,0xFFFFFFFF,0xFFE9E9E9), dark(0xFFFFFFFF,0xFFBDBDBD,0xFF000000,0xFF000000,0xFF161616))
        ThemeChoice.SYSTEM -> ThemePalette(light(0xFF3949AB,0xFF5C6BC0,0xFFF7F7FA,0xFFFFFFFF,0xFFE9EAF2), dark(0xFFB9C1FF,0xFFB9C0D8,0xFF090A0E,0xFF12141A,0xFF20232D))
    }
}

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : FragmentActivity() {
    private val subjects = mutableStateListOf<Subject>()
    private val folders = mutableStateListOf<Folder>()
    private val documents = mutableStateListOf<LocalDocument>()
    private var selectedSubject by mutableStateOf<Subject?>(null)
    private var selectedCategory by mutableStateOf("Notes")
    private var imageGallery by mutableStateOf<List<LocalDocument>>(emptyList())
    private var imageGalleryStart by mutableIntStateOf(0)
    private var searchOpen by mutableStateOf(false)
    private var trashOpen by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    private var shareWatermarkEnabled by mutableStateOf(true)
    private var shareOptionsOpen by mutableStateOf(false)
    private var shareBusy by mutableStateOf(false)
    private var shareBusyTitle by mutableStateOf("")
    private var shareBusyProgress by mutableFloatStateOf(0f)
    private var pdfNameDialog by mutableStateOf(false)
    private var pendingPdfDocs: List<LocalDocument> = emptyList()
    private var saveJob: Job? = null
    private val isPremiumUser: Boolean get() = BuildConfig.PREMIUM_BUILD
    private var locked by mutableStateOf(false)
    private var importing by mutableStateOf(false)
    private var importTotal by mutableIntStateOf(0)
    private var importDone by mutableIntStateOf(0)
    private var importCancelled by mutableStateOf(false)
    private var themeChoice by mutableStateOf(ThemeChoice.OBSIDIAN)
    private var hidePrivateFolders by mutableStateOf(false)
    private var appearanceMode by mutableStateOf(AppearanceMode.SYSTEM)
    private var privacyDashboard by mutableStateOf(false)
    private var supportOpen by mutableStateOf(false)
    private var deleteSubjectTarget: Subject? by mutableStateOf(null)
    private var incomingShare by mutableStateOf<IncomingShare?>(null)
    private val prefs by lazy { getSharedPreferences("liquid_space_data", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        // Do not seed the library with demo/default subjects.
        // Remove the old demo subjects once if they were created by an earlier build.
        val legacyDemoNames = setOf("Computer Science", "Mathematics", "Digital Electronics")
        if (documents.isEmpty() && subjects.all { it.name in legacyDemoNames }) {
            subjects.clear()
            folders.clear()
            saveData()
        }
        locked = prefs.getBoolean("app_lock", false)
        hidePrivateFolders = prefs.getBoolean("hide_private", false)
        shareWatermarkEnabled = if (isPremiumUser) prefs.getBoolean("share_watermark", true) else true
        appearanceMode = runCatching { AppearanceMode.valueOf(prefs.getString("appearance", AppearanceMode.SYSTEM.name) ?: AppearanceMode.SYSTEM.name) }.getOrDefault(AppearanceMode.SYSTEM)
        setContent { LiquidSpaceApp() }
        if (locked) authenticate()
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncomingShare(intent) }
    override fun onStop() {
        saveJob?.cancel()
        saveDataNow()
        super.onStop()
    }

    // Mutations are frequent (favorites, recent files, settings). Debounce persistence so
    // JSON serialization never runs on the UI thread for every small interaction.
    private fun saveData() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(250)
            val payload = snapshotData()
            withContext(Dispatchers.IO) { writeData(payload) }
        }
    }

    private data class PersistedState(
        val theme: String, val locked: Boolean, val hidePrivate: Boolean,
        val watermark: Boolean, val appearance: String,
        val subjects: List<Subject>, val folders: List<Folder>, val documents: List<LocalDocument>
    )

    private fun snapshotData(): PersistedState = PersistedState(
        themeChoice.name, locked, hidePrivateFolders, shareWatermarkEnabled, appearanceMode.name,
        subjects.toList(), folders.toList(), documents.map { it.copy() }
    )

    private fun writeData(state: PersistedState) {
        val subjectJson = JSONArray().apply { state.subjects.forEach {
            put(JSONObject().apply { put("id",it.id); put("name",it.name); put("icon",it.icon); put("color",it.color) })
        } }.toString()
        val folderJson = JSONArray().apply { state.folders.forEach {
            put(JSONObject().apply { put("id",it.id); put("subjectId",it.subjectId); put("name",it.name); put("private",it.isPrivate) })
        } }.toString()
        val documentJson = JSONArray().apply { state.documents.forEach {
            put(JSONObject().apply {
                put("id",it.id); put("name",it.name); put("path",it.path); put("size",it.size);
                put("subjectId",it.subjectId); put("category",it.category); put("mimeType",it.mimeType);
                put("favorite",it.favorite); put("trashed",it.trashed); put("deletedAt",it.deletedAt);
                put("addedAt",it.addedAt); put("lastOpenedAt",it.lastOpenedAt);
                put("folderId",it.folderId ?: -1L); put("checksum",it.checksum)
            })
        } }.toString()
        prefs.edit()
            .putString("theme", state.theme)
            .putBoolean("app_lock", state.locked)
            .putBoolean("hide_private", state.hidePrivate)
            .putBoolean("share_watermark", state.watermark)
            .putString("appearance", state.appearance)
            .putString("subjects", subjectJson)
            .putString("folders", folderJson)
            .putString("documents", documentJson)
            .apply()
    }

    private fun saveDataNow() { writeData(snapshotData()) }

    private fun loadData() {
        runCatching {
            themeChoice = ThemeChoice.valueOf(prefs.getString("theme", ThemeChoice.OBSIDIAN.name) ?: ThemeChoice.OBSIDIAN.name)
            prefs.getString("subjects", null)?.let { a -> subjects.clear(); val arr=JSONArray(a); for(i in 0 until arr.length()){val o=arr.getJSONObject(i);subjects += Subject(o.getLong("id"),o.getString("name"),o.optString("icon","📚"),o.optLong("color",0xFF6C63FF))} }
            prefs.getString("folders", null)?.let { a -> folders.clear(); val arr=JSONArray(a); for(i in 0 until arr.length()){val o=arr.getJSONObject(i);folders += Folder(o.getLong("id"),o.getLong("subjectId"),o.getString("name"),o.optBoolean("private",false))} }
            prefs.getString("documents", null)?.let { a -> documents.clear(); val arr=JSONArray(a); for(i in 0 until arr.length()){
                val o=arr.getJSONObject(i); val path=o.getString("path"); val f=File(path)
                if(f.exists()) documents += LocalDocument(o.optLong("id",path.hashCode().toLong()),o.getString("name"),path,o.optLong("size",f.length()),o.getLong("subjectId"),o.getString("category"),o.optString("mimeType",mimeFromName(o.getString("name"))),o.optBoolean("favorite",false),o.optBoolean("trashed",false),o.optLong("deletedAt",0),o.optLong("addedAt",System.currentTimeMillis()),o.optLong("lastOpenedAt",0L),o.optLong("folderId",-1).takeIf{it>=0},o.optString("checksum",""))
            } }
        }
        saveData()
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> {
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.toList() ?: emptyList()
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
                }
            }
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            val firstName = queryName(uris.first()) ?: "Shared file"
            incomingShare = IncomingShare(uris, if (uris.size == 1) firstName else "${uris.size} shared files")
        }
    }
    private fun queryName(uri: Uri): String? = contentResolver.query(uri,null,null,null,null)?.use { c -> val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0&&c.moveToFirst()) c.getString(i) else null }

    private fun importUri(uri: Uri, subjectId: Long, category: String) {
        copyImportedDocument(uri, subjectId, category)?.let { documents += it; saveData() }
    }
    private fun sha256(file:File):String {
        val md=java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use{input->val buffer=ByteArray(8192);var n=input.read(buffer);while(n>0){md.update(buffer,0,n);n=input.read(buffer)}}
        return md.digest().joinToString(""){"%02x".format(it)}
    }
    private fun mimeFromName(name:String)=when(name.substringAfterLast('.').lowercase()){
        "jpg","jpeg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";"gif"->"image/gif";"bmp"->"image/bmp";"heic"->"image/heic";"pdf"->"application/pdf";"doc"->"application/msword";"docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";"ppt"->"application/vnd.ms-powerpoint";"pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";"xls"->"application/vnd.ms-excel";"xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";"txt"->"text/plain";else->"application/octet-stream"
    }

    @Composable fun LiquidSpaceApp() {
        val palette=paletteFor(themeChoice); val systemDark=androidx.compose.foundation.isSystemInDarkTheme(); val dark=when(appearanceMode){AppearanceMode.LIGHT->false;AppearanceMode.DARK->true;AppearanceMode.SYSTEM->systemDark}
        val scheme=if(dark) palette.dark else palette.light
        val themeStyle=styleFor(themeChoice)
        val baseTypography=Typography()
        val themeTypography=baseTypography.copy(
            headlineLarge=baseTypography.headlineLarge.copy(fontWeight=FontWeight.Bold,letterSpacing=(-0.6).sp),
            headlineMedium=baseTypography.headlineMedium.copy(fontWeight=FontWeight.SemiBold,letterSpacing=(-0.3).sp),
            titleLarge=baseTypography.titleLarge.copy(fontWeight=FontWeight.SemiBold,letterSpacing=(-0.2).sp),
            bodyLarge=baseTypography.bodyLarge.copy(letterSpacing=(-0.1).sp),
            labelLarge=baseTypography.labelLarge.copy(fontWeight=FontWeight.SemiBold)
        )
        val shapes=Shapes(
            extraSmall=RoundedCornerShape((themeStyle.controlRadius-4).coerceAtLeast(6).dp),
            small=RoundedCornerShape(themeStyle.controlRadius.dp),
            medium=RoundedCornerShape(themeStyle.cardRadius.dp),
            large=RoundedCornerShape((themeStyle.cardRadius+4).dp),
            extraLarge=RoundedCornerShape((themeStyle.cardRadius+8).dp)
        )
        MaterialTheme(colorScheme=scheme, typography=themeTypography, shapes=shapes) {
            // Android system back should navigate through Liquid Space screens instead of
            // immediately finishing the activity. Only the root home screen exits.
            BackHandler(enabled = !locked) {
                when {
                    imageGallery.isNotEmpty() -> imageGallery = emptyList()
                    incomingShare != null -> incomingShare = null
                    importing -> { importCancelled = true; importing = false }
                    privacyDashboard -> privacyDashboard = false
                    shareOptionsOpen -> shareOptionsOpen = false
                    supportOpen -> supportOpen = false
                    deleteSubjectTarget != null -> deleteSubjectTarget = null
                    settingsOpen -> settingsOpen = false
                    trashOpen -> trashOpen = false
                    searchOpen -> searchOpen = false
                    selectedSubject != null -> selectedSubject = null
                    else -> finish()
                }
            }
            Crossfade(targetState=themeChoice, label="theme") {
                if(locked) LockScreen() else when {
                    settingsOpen -> SettingsScreen()
                    trashOpen -> TrashScreen()
                    searchOpen -> SearchScreen()
                    selectedSubject != null -> SubjectScreen(selectedSubject!!)
                    else -> HomeScreen()
                }
            }
            if(imageGallery.isNotEmpty()) ImageGalleryViewer(imageGallery,imageGalleryStart){imageGallery=emptyList()}
            if(importing) ImportDialog()
            if(privacyDashboard) PrivacyDialog()
            if(shareOptionsOpen) ShareOptionsDialog()
            if(shareBusy) ShareBusyDialog(shareBusyTitle,shareBusyProgress)
            if(pdfNameDialog) PdfNameDialog()
            if (!locked) incomingShare?.let { IncomingShareDialog(it) }
            if (!locked && supportOpen) SupportWidgetDialog()
        }
    }

    @Composable private fun HomeScreen(){
        // Keep the home feed derived from stable snapshots so scrolling does not
        // repeatedly traverse the full document list for every visible card.
        val visibleDocuments = remember(documents.size, documents.count { isDocumentVisible(it) }) {
            documents.filter { isDocumentVisible(it) }
        }
        val recent=remember(visibleDocuments.map { it.id to (if(it.lastOpenedAt>0) it.lastOpenedAt else it.addedAt) }){
            visibleDocuments.sortedByDescending{if(it.lastOpenedAt>0) it.lastOpenedAt else it.addedAt}.take(8)
        }
        val fav=remember(visibleDocuments.map { it.id to it.favorite }){
            visibleDocuments.filter{it.favorite}.take(8)
        }
        val totalBytes=remember(visibleDocuments.map { it.id to it.size }){ visibleDocuments.sumOf{it.size} }
        val fileCount=visibleDocuments.size
        val subjectFileCounts=remember(visibleDocuments.map { it.id to it.subjectId }){
            visibleDocuments.groupingBy{it.subjectId}.eachCount()
        }
        val subjectRows=((subjects.size+1)/2).coerceAtLeast(1)
        // Include grid spacing in the measured height. The previous fixed
        // calculation omitted the vertical gaps, so the final subject row was
        // covered by the Recent section on some screen sizes.
        val subjectGridHeight=(subjectRows*108 + (subjectRows-1)*10).dp
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            floatingActionButton={
                FloatingActionButton(
                    onClick={createSubjectDialog=true},
                    shape=MaterialTheme.shapes.large,
                    containerColor=MaterialTheme.colorScheme.primary,
                    contentColor=MaterialTheme.colorScheme.onPrimary
                ){Icon(Icons.Default.Add,null)}
            }
        ){pad->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding=PaddingValues(start=20.dp,end=20.dp,top=18.dp,bottom=104.dp),
                verticalArrangement=Arrangement.spacedBy(18.dp)
            ){
                item{
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Surface(
                            shape=RoundedCornerShape(15.dp),
                            color=MaterialTheme.colorScheme.primary.copy(alpha=.12f),
                            modifier=Modifier.size(48.dp)
                        ){
                            Box(contentAlignment=Alignment.Center){
                                Icon(Icons.Default.Inventory2,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(25.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)){
                            Text("YOUR LIBRARY",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                            Text("Liquid Space",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        }
                        BmcCupButton(compact=true,width=50.dp)
                        IconButton(onClick={settingsOpen=true}){
                            Icon(Icons.Default.Settings,"Settings",Modifier.size(24.dp))
                        }
                    }
                }
                item{HeroHeadline()}
                item{SearchBarPill{searchOpen=true}}
                item{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
                        HomeStatCard("${subjects.size}","Subjects",Modifier.weight(1f))
                        HomeStatCard("$fileCount","Files",Modifier.weight(1f))
                        HomeStatCard(formatSize(totalBytes),"Used",Modifier.weight(1f))
                    }
                }
                item{
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text("Subjects",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        TextButton(onClick={createSubjectDialog=true}){Text("New")}
                    }
                }
                item{
                    if(subjects.isEmpty()){
                        EmptyHomeCard{createSubjectDialog=true}
                    }else{
                        LazyVerticalGrid(
                            columns=GridCells.Fixed(2),
                            modifier=Modifier.height(subjectGridHeight),
                            userScrollEnabled=false,
                            horizontalArrangement=Arrangement.spacedBy(10.dp),
                            verticalArrangement=Arrangement.spacedBy(10.dp)
                        ){
                            items(subjects,key={it.id}){subject->
                                CompactSubjectCard(subject, subjectFileCounts[subject.id] ?: 0)
                            }
                        }
                    }
                }
                if(recent.isNotEmpty()){
                    item{SectionTitle("Recent")}
                    item{DocumentStrip(recent)}
                }
                if(fav.isNotEmpty()){
                    item{SectionTitle("Favorites")}
                    item{DocumentStrip(fav)}
                }
            }
        }
        if(createSubjectDialog) NewSubjectDialog()
        deleteSubjectTarget?.let { subject -> DeleteSubjectDialog(subject) }
        editSubjectTarget?.let { subject -> EditSubjectDialog(subject) }
    }

    @Composable private fun HeroHeadline(){
        val phrases=listOf("Everything in place","Study simplified","Your files. Your space.")
        var visible by remember{mutableStateOf(phrases.last())}
        LaunchedEffect(Unit){
            // Play the headline animation only once on the app's first launch.
            // After it has played, keep the final phrase visible without replaying
            // when Home recomposes or the user navigates back to it.
            val prefs=getSharedPreferences("liquid_space_prefs", MODE_PRIVATE)
            val hasPlayed=prefs.getBoolean("hero_headline_played", false)
            if(!hasPlayed){
                for(phrase in phrases){
                    for(i in 0..phrase.length){
                        visible=phrase.take(i)
                        kotlinx.coroutines.delay(42)
                    }
                    if(phrase != phrases.last()) kotlinx.coroutines.delay(1450)
                }
                visible=phrases.last()
                prefs.edit().putBoolean("hero_headline_played", true).apply()
            }else{
                visible=phrases.last()
            }
        }
        Surface(
            shape=MaterialTheme.shapes.extraLarge,
            color=MaterialTheme.colorScheme.surface,
            tonalElevation=2.dp,
            modifier=Modifier.fillMaxWidth()
        ){
            Column(Modifier.padding(horizontal=20.dp,vertical=20.dp)){
                Text(
                    visible.ifBlank{"Everything in place"},
                    style=MaterialTheme.typography.headlineMedium,
                    color=MaterialTheme.colorScheme.onSurface,
                    minLines=1
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Local • Private • Organized",
                    style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable private fun CompactSubjectCard(s:Subject, count:Int){
        val tint=Color(s.color)
        val itemAlpha by animateFloatAsState(1f, animationSpec=tween(260, easing=FastOutSlowInEasing), label="subjectAlpha")
        Card(
            modifier=Modifier.fillMaxWidth().height(108.dp).graphicsLayer { alpha=itemAlpha }.combinedClickable(onClick={selectedSubject=s},onLongClick={editSubjectTarget=s}),
            shape=MaterialTheme.shapes.medium,
            colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=styleFor(themeChoice).borderAlpha)),
            elevation=CardDefaults.cardElevation(defaultElevation=styleFor(themeChoice).cardElevation.dp)
        ){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.SpaceBetween){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha=.15f)),contentAlignment=Alignment.Center){
                        Text(s.icon,fontSize=18.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(18.dp))
                }
                Column{
                    Text(s.name,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("$count files",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable private fun EmptyHomeCard(onAdd:()->Unit){
        OutlinedCard(onClick=onAdd,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()){
            Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Default.AddCircleOutline,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column{
                    Text("Create your first subject",fontWeight=FontWeight.SemiBold)
                    Text("Start building your local library",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable private fun SearchBarPill(onClick:()->Unit)=Surface(
        onClick=onClick,
        shape=MaterialTheme.shapes.medium,
        color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.72f),
        modifier=Modifier.fillMaxWidth().height(54.dp)
    ){
        Row(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(Icons.Default.Search,"Search",Modifier.size(23.dp))
            Spacer(Modifier.width(12.dp))
            Text("Search",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=16.sp)
            Spacer(Modifier.weight(1f))
            Text("⌘",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable private fun SectionTitle(t:String)=Row(
        Modifier.fillMaxWidth(),
        verticalAlignment=Alignment.CenterVertically
    ){Text(t,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))}

    @Composable private fun HomeStatCard(value:String,label:String,modifier:Modifier=Modifier){
        Card(
            modifier=modifier,
            shape=MaterialTheme.shapes.medium,
            colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.10f)),
            elevation=CardDefaults.cardElevation(defaultElevation=styleFor(themeChoice).cardElevation.dp)
        ){
            Column(Modifier.padding(horizontal=13.dp,vertical=12.dp)){
                Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private var createSubjectDialog by mutableStateOf(false)
    private var editSubjectTarget: Subject? by mutableStateOf(null)
    private fun isDocumentVisible(doc:LocalDocument):Boolean {
        if(doc.trashed) return false
        val f=doc.folderId?.let{id->folders.firstOrNull{it.id==id}}
        return !(hidePrivateFolders && f?.isPrivate==true)
    }

    private fun greeting()=when(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)){in 5..11->"Good morning 👋";in 12..17->"Good afternoon 👋";else->"Good evening 👋"}
    @Composable private fun BmcCupMark(modifier: Modifier = Modifier){
        Canvas(modifier.size(32.dp)) {
            val stroke = 2.6.dp.toPx()
            val black = Color(0xFF171717)
            val yellow = Color(0xFFFFD84D)
            drawRoundRect(
                color = yellow,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * .27f, size.height * .36f),
                size = androidx.compose.ui.geometry.Size(size.width * .46f, size.height * .49f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .10f),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
            drawRoundRect(
                color = black,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * .27f, size.height * .36f),
                size = androidx.compose.ui.geometry.Size(size.width * .46f, size.height * .49f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .10f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawRoundRect(
                color = black,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * .19f, size.height * .17f),
                size = androidx.compose.ui.geometry.Size(size.width * .62f, size.height * .25f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .10f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawLine(black, androidx.compose.ui.geometry.Offset(size.width*.27f,size.height*.32f), androidx.compose.ui.geometry.Offset(size.width*.73f,size.height*.32f), stroke)
        }
    }

    @Composable private fun BmcCupButton(compact: Boolean, width: androidx.compose.ui.unit.Dp){
        Surface(
            onClick = { supportOpen = true },
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFFF713F),
            modifier = Modifier.height(54.dp).width(width)
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                BmcCupMark(Modifier.size(if (compact) 32.dp else 30.dp))
                if (!compact) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Support",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
    @Composable private fun SubjectCard(s:Subject){
        val count=documents.count{it.subjectId==s.id&&isDocumentVisible(it)}
        var menuOpen by remember(s.id){mutableStateOf(false)}
        val tint=Color(s.color)
        Box(Modifier.fillMaxWidth()){
            Card(
                modifier=Modifier.fillMaxWidth().combinedClickable(
                    onClick={selectedSubject=s},
                    onLongClick={menuOpen=true}
                ),
                shape=MaterialTheme.shapes.medium,
                colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=styleFor(themeChoice).borderAlpha)),
                elevation=CardDefaults.cardElevation(defaultElevation=styleFor(themeChoice).cardElevation.dp)
            ){
                Row(Modifier.padding(17.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha=.18f)),contentAlignment=Alignment.Center){
                        Text(s.icon,style=MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)){
                        Text(s.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("$count file${if(count==1)"" else "s"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}){
                DropdownMenuItem(text={Text("Edit subject")},leadingIcon={Icon(Icons.Default.Edit,null)},onClick={menuOpen=false;editSubjectTarget=s})
                DropdownMenuItem(text={Text("Move up")},leadingIcon={Icon(Icons.Default.KeyboardArrowUp,null)},onClick={menuOpen=false;moveSubject(s,-1)})
                DropdownMenuItem(text={Text("Move down")},leadingIcon={Icon(Icons.Default.KeyboardArrowDown,null)},onClick={menuOpen=false;moveSubject(s,1)})
                DropdownMenuItem(text={Text("Delete subject")},leadingIcon={Icon(Icons.Default.Delete,null)},onClick={menuOpen=false;deleteSubjectTarget=s})
            }
        }
    }
    private fun moveSubject(subject:Subject,direction:Int){
        val index=subjects.indexOfFirst{it.id==subject.id}
        val target=index+direction
        if(index>=0 && target in subjects.indices){
            val item=subjects.removeAt(index)
            subjects.add(target,item)
            saveData()
        }
    }

    @Composable private fun DocumentStrip(list:List<LocalDocument>){
        LazyVerticalGrid(
            columns=GridCells.Fixed(2),
            modifier=Modifier.height(180.dp),
            userScrollEnabled=false,
            horizontalArrangement=Arrangement.spacedBy(10.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)
        ){
            items(list.take(4),key={it.id}){doc->
                MiniDocCard(doc)
            }
        }
    }
    @Composable private fun MiniDocCard(doc:LocalDocument){
        val itemAlpha by animateFloatAsState(1f, animationSpec=tween(220, easing=FastOutSlowInEasing), label="recentAlpha")
        val preview by produceState<android.graphics.Bitmap?>(initialValue=null,key1=doc.path){ value=withContext(Dispatchers.IO){createPreviewBitmap(doc, 360)} }
        Card(modifier=Modifier.fillMaxSize().graphicsLayer { alpha=itemAlpha }.combinedClickable(onClick={if(doc.isImage)openImageGallery(listOf(doc),0)else openFile(doc)},onLongClick={toggleFavorite(doc)}),shape=MaterialTheme.shapes.medium,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(defaultElevation=styleFor(themeChoice).cardElevation.dp)){Box(Modifier.fillMaxSize()){if(preview!=null)Image(preview!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else FileTypeTile(doc);Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(.52f)).padding(8.dp)){Text(doc.name,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White,style=MaterialTheme.typography.labelMedium)}}}}

    @Composable private fun SubjectScreen(subject:Subject){
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()) startImport(uris,subject.id,selectedCategory)}
        val cats=listOf("Notes","Syllabus & Exams","Practical"); val list=documents.filter{it.subjectId==subject.id&&isDocumentVisible(it)&&it.category==selectedCategory&&(activeFolderId==null||it.folderId==activeFolderId)}.sortedWith(sortComparator)
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            topBar={
                if(selection.isNotEmpty()) {
                    SelectionTopBar()
                } else {
                    Column {
                        TopAppBar(
                            title={ Text("${subject.icon} ${subject.name}", maxLines=1, overflow=TextOverflow.Ellipsis) },
                            navigationIcon={IconButton(onClick={selectedSubject=null}){Icon(Icons.Default.ArrowBack,null)}},
                            actions={IconButton(onClick={searchOpen=true}){Icon(Icons.Default.Search,null)}}
                        )
                    }
                }
            },
            floatingActionButton={FloatingActionButton(onClick={showAddMenu=true},shape=MaterialTheme.shapes.large,containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary){Icon(Icons.Default.Add,null)}}
        ){pad->
            Column(Modifier.fillMaxSize().padding(pad)){
                ScrollableTabRow(selectedTabIndex=cats.indexOf(selectedCategory),edgePadding=12.dp){cats.forEach{cat->
                    Tab(selected=cat==selectedCategory,onClick={selectedCategory=cat},text={Text(cat)})
                }}
                val myFolders=folders.filter{it.subjectId==subject.id&&(!hidePrivateFolders||!it.isPrivate)}
                if(myFolders.isNotEmpty()){
                    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal=14.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        AssistChip(onClick={activeFolderId=null},label={Text("All")},leadingIcon={if(activeFolderId==null)Icon(Icons.Default.Check,null) else null})
                        myFolders.forEach{f->AssistChip(onClick={activeFolderId=f.id},label={Text("📁 ${f.name}")},leadingIcon={if(activeFolderId==f.id)Icon(Icons.Default.Check,null) else null})}
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp),horizontalArrangement=Arrangement.End){FilterSortButton()}
                if(list.isEmpty()) EmptyState("Nothing here yet","Add photos or documents to this section") else LazyVerticalGrid(
                    columns=GridCells.Adaptive(145.dp),modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)
                ){items(list,key={it.id}){doc->GalleryDocumentCard(doc,list)}}
            }
        }
        if(showAddMenu)AddMenu(picker)
        if(renameTarget!=null)RenameDialog(renameTarget!!)
        if(detailsTarget!=null)DetailsDialog(detailsTarget!!)
        if(moveTarget!=null)MoveDialog(moveTarget!!)
    }
    private var showAddMenu by mutableStateOf(false)
    private val selection=mutableStateListOf<Long>()
    private var renameTarget:LocalDocument? by mutableStateOf(null)
    private var detailsTarget:LocalDocument? by mutableStateOf(null)
    private var moveTarget:LocalDocument? by mutableStateOf(null)
    private var sortMode by mutableStateOf("Newest")
    private var activeFolderId by mutableStateOf<Long?>(null)
    private val sortComparator:Comparator<LocalDocument> get()=when(sortMode){"Oldest"->compareBy{it.addedAt};"Name"->compareBy(String.CASE_INSENSITIVE_ORDER){it.name};"Size"->compareByDescending{it.size};else->compareByDescending{it.addedAt}}

    @Composable private fun FilterSortButton(){var open by remember{mutableStateOf(false)};Box{TextButton(onClick={open=true}){Icon(Icons.Default.Sort,null);Spacer(Modifier.width(4.dp));Text(sortMode)};DropdownMenu(expanded=open,onDismissRequest={open=false}){listOf("Newest","Oldest","Name","Size").forEach{m->DropdownMenuItem(text={Text(m)},onClick={sortMode=m;open=false})}}}}
    @Composable private fun GalleryDocumentCard(doc:LocalDocument,visible:List<LocalDocument>){
        val preview by produceState<android.graphics.Bitmap?>(initialValue=null,key1=doc.path){
            value=withContext(Dispatchers.IO){createPreviewBitmap(doc, 360)}
        }
        val selected=selection.contains(doc.id)
        Card(modifier=Modifier.fillMaxWidth().aspectRatio(.82f).combinedClickable(
            onClick={if(selection.isNotEmpty())toggleSelection(doc)else if(doc.isImage){val imgs=visible.filter{it.isImage};openImageGallery(imgs,imgs.indexOfFirst{it.id==doc.id}.coerceAtLeast(0))}else openFile(doc)},
            onLongClick={toggleSelection(doc)}),shape=RoundedCornerShape(17.dp),border=if(selected)BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null){
            Box(Modifier.fillMaxSize()){
                if(preview!=null) Image(preview!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) else FileTypeTile(doc)
                if(doc.favorite)Icon(Icons.Default.Star,null,Modifier.align(Alignment.TopEnd).padding(8.dp),tint=Color(0xFFFFD54F))
                if(selected)Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center){Icon(Icons.Default.Check,null,tint=Color.White)}
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(.58f)).padding(9.dp)){
                    Text(doc.name,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)
                    Text(formatSize(doc.size),color=Color.White.copy(.75f),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    @Composable private fun FileTypeTile(doc:LocalDocument){Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(if(doc.isImage)Icons.Default.Image else Icons.Default.Description,null,Modifier.size(48.dp));Spacer(Modifier.height(8.dp));Text(doc.extension,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)}}
    @Composable private fun EmptyState(title:String,subtitle:String){Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(Icons.Default.FolderOpen,null,Modifier.size(60.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(12.dp));Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)}}

    @Composable
    private fun IncomingShareDialog(share: IncomingShare) {
        var selectedSubjectId by remember(share) { mutableStateOf<Long?>(null) }
        var creatingSubject by remember(share) { mutableStateOf(false) }
        var newSubjectName by remember(share) { mutableStateOf("") }
        var category by remember(share) { mutableStateOf("Notes") }
        var folderId by remember(share) { mutableStateOf<Long?>(null) }
        val selectedSubject = subjects.firstOrNull { it.id == selectedSubjectId }
        val availableFolders = selectedSubject?.let { subject ->
            folders.filter { it.subjectId == subject.id && (!hidePrivateFolders || !it.isPrivate) }
        } ?: emptyList()

        AlertDialog(
            onDismissRequest = { incomingShare = null },
            title = {
                Column {
                    Text("Add to Liquid Space", fontWeight = FontWeight.Bold)
                    Text(
                        share.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selectedSubject == null && !creatingSubject) {
                        Text("Choose a subject", fontWeight = FontWeight.SemiBold)
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(subjects, key = { it.id }) { subject ->
                                ListItem(
                                    headlineContent = { Text("${subject.icon} ${subject.name}") },
                                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { selectedSubjectId = subject.id }
                                )
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text("＋ Create new subject") },
                                    leadingContent = { Icon(Icons.Default.Add, null) },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { creatingSubject = true }
                                )
                            }
                        }
                    } else {
                        if (creatingSubject) {
                            Text("New subject", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = newSubjectName,
                                onValueChange = { newSubjectName = it },
                                label = { Text("Subject name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedSubjectId = null }) {
                                    Icon(Icons.Default.ArrowBack, "Back")
                                }
                                Text(
                                    "${selectedSubject?.icon ?: "📚"} ${selectedSubject?.name ?: "Subject"}",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text("Place in", fontWeight = FontWeight.SemiBold)
                        listOf("Notes", "Syllabus & Exams", "Practical").forEach { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = c },
                                label = { Text(c) },
                                leadingIcon = {
                                    if (category == c) Icon(Icons.Default.Check, null)
                                }
                            )
                        }

                        if (!creatingSubject && availableFolders.isNotEmpty()) {
                            Text("Folder", fontWeight = FontWeight.SemiBold)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = folderId == null,
                                    onClick = { folderId = null },
                                    label = { Text("No folder") }
                                )
                                availableFolders.forEach { folder ->
                                    FilterChip(
                                        selected = folderId == folder.id,
                                        onClick = { folderId = folder.id },
                                        label = { Text("📁 ${folder.name}") }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (selectedSubject != null || creatingSubject) {
                    TextButton(
                        enabled = !creatingSubject || newSubjectName.isNotBlank(),
                        onClick = {
                            val subject = if (creatingSubject) {
                                Subject(System.currentTimeMillis(), newSubjectName.trim(), "📚", 0xFF6C63FF).also { subjects += it }
                            } else selectedSubject!!
                            incomingShare = null
                            if (folderId != null && !creatingSubject) {
                                // Folder selection is applied after import through the document metadata.
                            }
                            if (share.uris.isNotEmpty()) {
                                importTotal = share.uris.size
                                importDone = 0
                                importCancelled = false
                                importing = true
                                Thread {
                                    val imported = mutableListOf<LocalDocument>()
                                    share.uris.forEach { uri ->
                                        if (!importCancelled) {
                                            copyImportedDocument(uri, subject.id, category)?.let { doc ->
                                                if (folderId != null) doc.folderId = folderId
                                                imported += doc
                                            }
                                            runOnUiThread { importDone++ }
                                        }
                                    }
                                    runOnUiThread {
                                        documents.addAll(imported)
                                        saveData()
                                        importing = false
                                    }
                                }.start()
                            }
                            saveData()
                        }
                    ) { Text("Add") }
                }
            },
            dismissButton = {
                TextButton(onClick = { incomingShare = null }) { Text("Cancel") }
            }
        )
    }

    @Composable
    private fun AddMenu(picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text("Add") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListItem(
                        headlineContent = { Text("🖼️ Photos") },
                        modifier = Modifier.clickable {
                            showAddMenu = false
                            picker.launch(arrayOf("image/*"))
                        }
                    )
                    ListItem(
                        headlineContent = { Text("📄 Documents") },
                        modifier = Modifier.clickable {
                            showAddMenu = false
                            picker.launch(arrayOf("*/*"))
                        }
                    )
                    ListItem(
                        headlineContent = { Text("📁 Folder") },
                        modifier = Modifier.clickable {
                            showAddMenu = false
                            createFolderDialog = true
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddMenu = false }) { Text("Cancel") }
            }
        )
        if (createFolderDialog) CreateFolderDialog()
    }
    private var createFolderDialog by mutableStateOf(false)
    private fun deleteSubject(subject:Subject){
        documents.filter{it.subjectId==subject.id}.forEach{runCatching{File(it.path).delete()}}
        documents.removeAll{it.subjectId==subject.id}
        folders.removeAll{it.subjectId==subject.id}
        subjects.removeAll{it.id==subject.id}
        if(selectedSubject?.id==subject.id) selectedSubject=null
        saveData()
    }

    @Composable private fun DeleteSubjectDialog(subject:Subject){
        AlertDialog(
            onDismissRequest={deleteSubjectTarget=null},
            title={Text("Delete ${subject.name}?")},
            text={Text("This will permanently remove the subject, its folders, and all files inside it from this device.")},
            confirmButton={TextButton(onClick={deleteSubject(subject);deleteSubjectTarget=null}){Text("Delete",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={deleteSubjectTarget=null}){Text("Cancel")}}
        )
    }

    @Composable private fun SupportWidgetDialog(){
        ModalBottomSheet(
            onDismissRequest={supportOpen=false},
            shape=RoundedCornerShape(topStart=30.dp,topEnd=30.dp),
            containerColor=MaterialTheme.colorScheme.background,
            dragHandle={
                Box(Modifier.padding(top=8.dp).width(54.dp).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(.18f)))
            }
        ){
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp).padding(bottom=22.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                Box(Modifier.fillMaxWidth().height(34.dp)){
                    IconButton(onClick={supportOpen=false},modifier=Modifier.align(Alignment.CenterEnd)){
                        Icon(Icons.Default.Close,"Close")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(78.dp),contentAlignment=Alignment.Center){
                    BmcCupMark(Modifier.size(72.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("Support Liquid Space",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "If you find this app helpful, consider\nsupporting via Buy Me a Coffee.",
                    textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                    style=MaterialTheme.typography.bodyLarge,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(22.dp))
                Surface(
                    modifier=Modifier.fillMaxWidth().height(82.dp),
                    shape=RoundedCornerShape(20.dp),
                    color=Color(0xFFFFF0E8)
                ){
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                        Text("❝  Love from Liquid  ❞",color=Color(0xFFFF5F20),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick={openSupportPage()},
                    modifier=Modifier.fillMaxWidth().height(58.dp),
                    shape=RoundedCornerShape(30.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFFF713F))
                ){
                    BmcCupMark(Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Support on Buy Me a Coffee",fontWeight=FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.OpenInNew,null,modifier=Modifier.size(22.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Every coffee helps keep Liquid Space free and growing. 🧡",
                    textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                    style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable private fun NewSubjectDialog(){
        var name by remember{mutableStateOf("")}
        var icon by remember{mutableStateOf("📚")}
        var color by remember{mutableLongStateOf(0xFF6C63FF)}
        val icons=listOf("📚","💻","📐","🧪","⚡","🔬","📖","💡","🎨","🧮","🌐","📝")
        val colors=listOf(0xFF6C63FF,0xFF00897B,0xFFEF6C00,0xFFE53935,0xFF8E24AA,0xFF1E88E5,0xFF43A047,0xFFD81B60)
        AlertDialog(
            onDismissRequest={createSubjectDialog=false},
            title={Text("New subject")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                OutlinedTextField(name,{name=it},label={Text("Subject name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("Icon",fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){
                    icons.forEach{item->FilterChip(selected=icon==item,onClick={icon=item},label={Text(item)},shape=RoundedCornerShape(12.dp))}
                }
                Text("Accent",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    colors.forEach{c->Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).clickable{color=c},contentAlignment=Alignment.Center){
                        if(color==c)Icon(Icons.Default.Check,null,tint=Color.White,modifier=Modifier.size(18.dp))
                    }}
                }
            }},
            confirmButton={TextButton(onClick={
                if(name.isNotBlank()){subjects+=Subject(System.currentTimeMillis(),name.trim(),icon,color);saveData();name="";createSubjectDialog=false}
            }){Text("Create")}},
            dismissButton={TextButton(onClick={createSubjectDialog=false}){Text("Cancel")}}
        )
    }

    @Composable private fun EditSubjectDialog(subject:Subject){
        var name by remember(subject.id){mutableStateOf(subject.name)}
        var icon by remember(subject.id){mutableStateOf(subject.icon)}
        var color by remember(subject.id){mutableLongStateOf(subject.color)}
        val icons=listOf("📚","💻","📐","🧪","⚡","🔬","📖","💡","🎨","🧮","🌐","📝")
        val colors=listOf(0xFF6C63FF,0xFF00897B,0xFFEF6C00,0xFFE53935,0xFF8E24AA,0xFF1E88E5,0xFF43A047,0xFFD81B60)
        AlertDialog(
            onDismissRequest={editSubjectTarget=null},
            title={Text("Edit subject")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                OutlinedTextField(name,{name=it},label={Text("Subject name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("Icon",fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){
                    icons.forEach{item->FilterChip(selected=icon==item,onClick={icon=item},label={Text(item)},shape=RoundedCornerShape(12.dp))}
                }
                Text("Accent",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    colors.forEach{c->Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).clickable{color=c},contentAlignment=Alignment.Center){
                        if(color==c)Icon(Icons.Default.Check,null,tint=Color.White,modifier=Modifier.size(18.dp))
                    }}
                }
            }},
            confirmButton={TextButton(onClick={
                if(name.isNotBlank()){
                    val index=subjects.indexOfFirst{it.id==subject.id}
                    if(index>=0)subjects[index]=subject.copy(name=name.trim(),icon=icon,color=color)
                    saveData();editSubjectTarget=null
                }
            }){Text("Save")}},
            dismissButton={TextButton(onClick={editSubjectTarget=null}){Text("Cancel")}}
        )
    }

    @Composable private fun CreateFolderDialog(){
        var name by remember{mutableStateOf("")}; var privateFolder by remember{mutableStateOf(false)}; val subject=selectedSubject?:return
        AlertDialog(onDismissRequest={createFolderDialog=false},title={Text("Create custom folder")},text={Column{OutlinedTextField(name,{name=it},label={Text("Folder name")},singleLine=true);Spacer(Modifier.height(8.dp));Row(verticalAlignment=Alignment.CenterVertically){Text("Private folder",Modifier.weight(1f));Switch(privateFolder,{privateFolder=it})}}},confirmButton={TextButton(onClick={if(name.isNotBlank()){folders+=Folder(System.currentTimeMillis(),subject.id,name.trim(),privateFolder);saveData();name="";createFolderDialog=false}}){Text("Create")}},dismissButton={TextButton(onClick={createFolderDialog=false}){Text("Cancel")}})
    }

    private fun startImport(uris:List<Uri>,subjectId:Long,category:String){
        importTotal=uris.size;importDone=0;importCancelled=false;importing=true
        Thread {
            val imported = mutableListOf<LocalDocument>()
            uris.forEach { uri ->
                if (importCancelled) return@forEach
                val doc = copyImportedDocument(uri, subjectId, category)
                if (doc != null) imported += doc
                runOnUiThread { importDone++ }
            }
            runOnUiThread {
                documents.addAll(imported)
                saveData()
                importing=false
            }
        }.start()
    }
    private fun copyImportedDocument(uri:Uri,subjectId:Long,category:String):LocalDocument? = runCatching {
        val name=queryName(uri) ?: "Imported file"
        val safe=name.replace(Regex("[\\\\/:*?\"<>|]"),"_")
        val dir=File(filesDir,"documents/$subjectId/${category.replace("/","_")}").apply{mkdirs()}
        var out=File(dir,safe)
        if(out.exists()) out=File(dir,"${System.currentTimeMillis()}_$safe")
        contentResolver.openInputStream(uri)?.use{input->out.outputStream().use{input.copyTo(it)}} ?: return@runCatching null
        val checksum=sha256(out)
        if(documents.any{!it.trashed&&it.checksum.isNotBlank()&&it.checksum==checksum}){out.delete();return@runCatching null}
        LocalDocument(System.nanoTime(),out.name,out.absolutePath,out.length(),subjectId,category,contentResolver.getType(uri)?:mimeFromName(out.name),checksum=checksum)
    }.getOrNull()
    @Composable private fun ImportDialog(){AlertDialog(onDismissRequest={importCancelled=true;importing=false},title={Text("Importing files")},text={Column{LinearProgressIndicator(progress={if(importTotal==0)0f else importDone.toFloat()/importTotal});Spacer(Modifier.height(10.dp));Text("$importDone of $importTotal");Text("Files stay on this device.",color=MaterialTheme.colorScheme.onSurfaceVariant)}},confirmButton={TextButton(onClick={importCancelled=true;importing=false}){Text("Cancel")}})}

    @Composable private fun SelectionTopBar(){
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp){
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min=64.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={selection.clear()}){Icon(Icons.Default.Close,null)}
                Text("${selection.size} selected",Modifier.weight(1f),fontWeight=FontWeight.SemiBold,maxLines=1)
                if(selection.size==1){
                    IconButton(onClick={renameSelection()}){Icon(Icons.Default.Edit,null)}
                    IconButton(onClick={detailsSelection()}){Icon(Icons.Default.Info,null)}
                }
                IconButton(onClick={shareSelection()}){Icon(Icons.Default.Share,null)}
                IconButton(onClick={moveSelection()}){Icon(Icons.Default.DriveFileMove,null)}
                IconButton(onClick={deleteSelection()}){Icon(Icons.Default.Delete,null)}
            }
        }
    }
    private fun shareSelection(){
        val docs=selection.mapNotNull{documents.find{d->d.id==it}}.filter{!it.trashed}
        if(docs.isEmpty()) return
        shareOptionsOpen=true
    }

    @Composable private fun ShareBusyDialog(title:String,progress:Float){
        Dialog(onDismissRequest={/* Work is intentionally not cancellable mid-file */}){
            Card(
                shape=RoundedCornerShape(28.dp),
                colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
                modifier=Modifier.widthIn(min=280.dp,max=360.dp)
            ){
                Column(
                    Modifier.padding(28.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(16.dp)
                ){
                    val rotation by androidx.compose.animation.core.animateFloatAsState(
                        targetValue=360f,animationSpec=tween(900,easing=FastOutSlowInEasing),label="loader"
                    )
                    LaunchedEffect(Unit){ while(true){ kotlinx.coroutines.delay(900) } }
                    Box(Modifier.size(72.dp),contentAlignment=Alignment.Center){
                        CircularProgressIndicator(
                            progress={progress.coerceIn(0f,1f)},
                            modifier=Modifier.fillMaxSize(),
                            strokeWidth=5.dp
                        )
                        Text("${(progress*100).toInt()}%",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
                    }
                    Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(
                        "Please wait… Liquid Space is preparing your files.",
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress={progress.coerceIn(0f,1f)},
                        Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable private fun ShareOptionsDialog(){
        val docs=selection.mapNotNull{documents.find{d->d.id==it}}.filter{!it.trashed}
        val canPdf=docs.isNotEmpty() && docs.all{it.isImage || it.mimeType.startsWith("text/") || it.mimeType.equals("application/pdf",true)}
        AlertDialog(
            onDismissRequest={shareOptionsOpen=false},
            title={Text(if(docs.size==1)"Share file" else "Share ${docs.size} files")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                    ListItem(
                        headlineContent={Text("Share normally")},
                        supportingContent={Text(if(docs.size==1)"Original file · no watermark" else "Original files · no watermark")},
                        leadingContent={Icon(Icons.Default.Share,null)},
                        modifier=Modifier.clip(RoundedCornerShape(16.dp)).clickable{
                            shareOptionsOpen=false
                            shareDocumentsIndividually(docs)
                        }
                    )
                    ListItem(
                        headlineContent={Text("Share as ZIP")},
                        supportingContent={Text("Package all selected files into one archive")},
                        leadingContent={Icon(Icons.Default.FolderZip,null)},
                        modifier=Modifier.clip(RoundedCornerShape(16.dp)).clickable{
                            shareOptionsOpen=false
                            shareAsZip(docs)
                        }
                    )
                    if(canPdf){
                        ListItem(
                            headlineContent={Text("Make PDF")},
                            supportingContent={Text("Create one PDF from the selected images/text files")},
                            leadingContent={Icon(Icons.Default.PictureAsPdf,null)},
                            modifier=Modifier.clip(RoundedCornerShape(16.dp)).clickable{
                                shareOptionsOpen=false
                                pendingPdfDocs=docs
                                pdfNameDialog=true
                            }
                        )
                    }
                    if(!isPremiumUser){
                        Text(
                            "Normal sharing sends the original files without a watermark. ZIP/PDF exports follow the sharing watermark rules.",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp)
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Text("Show watermark",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                            Switch(shareWatermarkEnabled,{shareWatermarkEnabled=it;saveData()})
                        }
                        Text("Premium control: choose whether shared images/PDF pages carry the Liquid Space watermark.",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier=Modifier.padding(horizontal=16.dp))
                    }
                }
            },
            confirmButton={TextButton(onClick={shareOptionsOpen=false;selection.clear()}){Text("Cancel")}}
        )
    }

    @Composable private fun PdfNameDialog(){
        val docs=pendingPdfDocs
        val defaultName=remember(docs.map{it.id}.hashCode()){
            val subjectNames=docs.mapNotNull{doc->subjects.firstOrNull{it.id==doc.subjectId}?.name}.distinct()
            when {
                subjectNames.size==1 -> "${subjectNames.first()} Notes"
                subjectNames.size>1 -> "${subjectNames.first()} & ${subjectNames.size-1} more"
                else -> "Liquid Space Notes"
            }
        }
        var name by remember(defaultName){mutableStateOf(defaultName)}
        AlertDialog(
            onDismissRequest={
                pdfNameDialog=false
                pendingPdfDocs=emptyList()
            },
            title={Text("Name your PDF")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(
                        if(docs.size==1)"Give your exported PDF a name." else "Choose a name for your ${docs.size}-file PDF.",
                        style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value=name,
                        onValueChange={name=it},
                        singleLine=true,
                        label={Text("PDF name")},
                        suffix={Text(".pdf")},
                        modifier=Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton={
                TextButton(onClick={
                    val finalName=sanitizePdfName(name.ifBlank{defaultName})
                    pdfNameDialog=false
                    val selected=pendingPdfDocs
                    pendingPdfDocs=emptyList()
                    shareAsPdf(selected,finalName)
                }){Text("Create PDF")}
            },
            dismissButton={
                TextButton(onClick={
                    pdfNameDialog=false
                    pendingPdfDocs=emptyList()
                }){Text("Cancel")}
            }
        )
    }

    private fun sanitizePdfName(value:String):String{
        return value.trim()
            .replace(Regex("""[\\\\/:*?"<>|]"""),"_")
            .replace(Regex("""\s+""")," ")
            .trim('.',' ')
            .ifBlank{"Liquid Space Notes"}
            .take(100)
    }

    private fun startShareJob(
        title:String,
        work:suspend (onProgress:suspend (Float)->Unit)->Uri?
    ){
        if(shareBusy) return
        shareBusy=true
        shareBusyTitle=title
        shareBusyProgress=0f

        lifecycleScope.launch(Dispatchers.IO){
            val result = try {
                work { progress ->
                    withContext(Dispatchers.Main){
                        shareBusyProgress=progress.coerceIn(0f,1f)
                    }
                }
            } catch (error:Throwable) {
                error.printStackTrace()
                null
            }

            withContext(Dispatchers.Main){
                shareBusy=false
                if(result!=null){
                    selection.clear()
                    val mime=contentResolver.getType(result) ?: when{
                        result.toString().endsWith(".zip",true)->"application/zip"
                        result.toString().endsWith(".pdf",true)->"application/pdf"
                        else->"*/*"
                    }
                    val intent=Intent(Intent.ACTION_SEND).apply{
                        type=mime
                        putExtra(Intent.EXTRA_STREAM,result)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData=android.content.ClipData.newRawUri("Liquid Space",result)
                    }
                    runCatching{startActivity(Intent.createChooser(intent,title))}
                        .onFailure{
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "No app can share this file.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                }else{
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Could not create the shared file.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun shareDocumentsIndividually(docs:List<LocalDocument>){
        shareOptionsOpen=false
        if(docs.size==1){
            shareBusy=true;shareBusyTitle="Preparing file";shareBusyProgress=0f
            lifecycleScope.launch(Dispatchers.IO){
                val uri=runCatching{prepareNormalShareFile(docs.first())}.getOrNull()
                withContext(Dispatchers.Main){
                    shareBusy=false
                    if(uri!=null){
                        selection.clear()
                        val intent=Intent(Intent.ACTION_SEND).apply{
                            type=docs.first().mimeType.ifBlank{"*/*"}
                            putExtra(Intent.EXTRA_STREAM,uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData=android.content.ClipData.newRawUri("Liquid Space",uri)
                        }
                        runCatching{startActivity(Intent.createChooser(intent,"Share file"))}
                    }else android.widget.Toast.makeText(this@MainActivity,"Unable to prepare this file.",android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // Generate the outbound copies off the main thread. This is important for
        // multiple image shares because watermark generation can be memory-heavy.
        shareBusy=true;shareBusyTitle="Preparing ${docs.size} files";shareBusyProgress=0f
        lifecycleScope.launch(Dispatchers.IO){
            val uris=ArrayList<Uri>()
            docs.forEachIndexed{index,doc->
                prepareNormalShareFile(doc)?.let{uris+=it}
                withContext(Dispatchers.Main){shareBusyProgress=(index+1).toFloat()/docs.size}
            }
            withContext(Dispatchers.Main){
                shareBusy=false
                if(uris.isEmpty()){
                    android.widget.Toast.makeText(this@MainActivity,"Unable to prepare the selected files.",android.widget.Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                selection.clear()
                val intent=Intent(Intent.ACTION_SEND_MULTIPLE).apply{
                    type="*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching{startActivity(Intent.createChooser(intent,"Share ${uris.size} files"))}
                    .onFailure{android.widget.Toast.makeText(this@MainActivity,"No app can share these files.",android.widget.Toast.LENGTH_SHORT).show()}
            }
        }
    }

    private fun shareAsZip(docs:List<LocalDocument>){
        shareOptionsOpen=false
        startShareJob("Share ZIP"){onProgress->
            val out=File(cacheDir,"share_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(out)).use{zip->
                docs.forEachIndexed{index,doc->
                    val source=shareableFileForArchive(doc)
                    if(source!=null){
                        val safe=source.name.replace(Regex("[\\\\/:*?\"<>|]"),"_")
                        zip.putNextEntry(ZipEntry("${index+1}_$safe"))
                        FileInputStream(source).use{input->
                            val buffer=ByteArray(64*1024)
                            var read:Int
                            while(input.read(buffer).also{read=it}>0) zip.write(buffer,0,read)
                        }
                        zip.closeEntry()
                    }
                    onProgress((index+1).toFloat()/docs.size)
                }
                if(!isPremiumUser || shareWatermarkEnabled){
                    val notice="""Shared from Liquid Space
This archive was created by Liquid Space.
""".trimIndent().toByteArray()
                    zip.putNextEntry(ZipEntry("Liquid Space - sharing notice.txt"))
                    zip.write(notice)
                    zip.closeEntry()
                }
            }
            FileProvider.getUriForFile(this,"${packageName}.provider",out)
        }
    }

    private fun shareAsPdf(docs:List<LocalDocument>,pdfName:String){
        shareOptionsOpen=false
        startShareJob("Creating PDF"){onProgress->
            val out=File(cacheDir,"${sanitizePdfName(pdfName)}.pdf")
            val pdf=PdfDocument()
            var pageNumber=1
            try{
                docs.forEachIndexed{docIndex,doc->
                    if(doc.isImage){
                        val opts=BitmapFactory.Options().apply{
                            inPreferredConfig=Bitmap.Config.ARGB_8888
                            inJustDecodeBounds=false
                        }
                        var bmp=BitmapFactory.decodeFile(doc.path,opts)
                        if(bmp!=null){
                            val maxW=1600
                            val scale=(maxW.toFloat()/bmp.width).coerceAtMost(1f)
                            val w=(bmp.width*scale).toInt().coerceAtLeast(1)
                            val h=(bmp.height*scale).toInt().coerceAtLeast(1)
                            val resized=if(scale<1f)Bitmap.createScaledBitmap(bmp,w,h,true) else bmp
                            val page=pdf.startPage(PdfDocument.PageInfo.Builder(w,h,pageNumber++).create())
                            page.canvas.drawBitmap(resized,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG))
                            if(!isPremiumUser || shareWatermarkEnabled) drawPdfWatermark(page.canvas,w,h)
                            pdf.finishPage(page)
                            if(resized!==bmp)resized.recycle()
                            bmp.recycle()
                        }
                    }else if(doc.mimeType.equals("application/pdf",true)){
                        val renderer=runCatching{PdfRenderer(ParcelFileDescriptor.open(File(doc.path),ParcelFileDescriptor.MODE_READ_ONLY))}.getOrNull()
                        if(renderer!=null){
                            try{
                                for(pageIndex in 0 until renderer.pageCount){
                                    val sourcePage=renderer.openPage(pageIndex)
                                    val maxW=1600
                                    val scale=(maxW.toFloat()/sourcePage.width).coerceAtMost(1f)
                                    val w=(sourcePage.width*scale).toInt().coerceAtLeast(1)
                                    val h=(sourcePage.height*scale).toInt().coerceAtLeast(1)
                                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(AndroidColor.WHITE)
                                    sourcePage.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    val page=pdf.startPage(PdfDocument.PageInfo.Builder(w,h,pageNumber++).create())
                                    page.canvas.drawBitmap(bitmap,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG))
                                    if(!isPremiumUser || shareWatermarkEnabled) drawPdfWatermark(page.canvas,w,h)
                                    pdf.finishPage(page)
                                    bitmap.recycle()
                                    sourcePage.close()
                                }
                            }finally{renderer.close()}
                        }
                    }else if(doc.mimeType.startsWith("text/")){
                        val text=runCatching{File(doc.path).readText().take(12000)}.getOrDefault("")
                        val pageW=1240;val pageH=1754
                        val page=pdf.startPage(PdfDocument.PageInfo.Builder(pageW,pageH,pageNumber++).create())
                        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=AndroidColor.BLACK;textSize=28f}
                        var y=60f
                        text.lines().take(55).forEach{line->page.canvas.drawText(line.take(80),50f,y,paint);y+=30f}
                        if(!isPremiumUser || shareWatermarkEnabled)drawPdfWatermark(page.canvas,pageW,pageH)
                        pdf.finishPage(page)
                    }
                    onProgress((docIndex+1).toFloat()/docs.size)
                }
                FileOutputStream(out).use{pdf.writeTo(it)}
            }finally{pdf.close()}
            if(!out.exists()||out.length()==0L) null else FileProvider.getUriForFile(this,"${packageName}.provider",out)
        }
    }

    private fun toggleFavoriteSelection(){selection.mapNotNull{documents.find{d->d.id==it}}.forEach{toggleFavorite(it)};selection.clear()}
    private fun renameSelection(){documents.firstOrNull{it.id==selection.firstOrNull()}?.let{renameTarget=it};selection.clear()}
    private fun detailsSelection(){documents.firstOrNull{it.id==selection.firstOrNull()}?.let{detailsTarget=it};selection.clear()}
    private fun toggleSelection(doc:LocalDocument){if(selection.contains(doc.id))selection.remove(doc.id)else selection.add(doc.id)}
    private fun toggleFavorite(doc:LocalDocument){doc.favorite=!doc.favorite;saveData()}
    private fun deleteSelection(){selection.mapNotNull{documents.find{d->d.id==it}}.forEach{it.trashed=true;it.deletedAt=System.currentTimeMillis()};selection.clear();saveData()}
    private fun moveSelection(){documents.find{it.id==selection.firstOrNull()}?.let{moveTarget=it}}
    private fun applyMove(category:String,folderId:Long?){
        val ids=if(selection.isNotEmpty())selection.toList() else listOfNotNull(moveTarget?.id)
        ids.mapNotNull{id->documents.find{it.id==id}}.forEach{doc->
            val folderName=folderId?.let{"folder_$it"} ?: category.replace("/","_")
            val dir=File(filesDir,"documents/${doc.subjectId}/$folderName").apply{mkdirs()}
            var target=File(dir,doc.name);if(target.absolutePath!=doc.path&&target.exists())target=File(dir,"${System.currentTimeMillis()}_${doc.name}")
            val moved=if(File(doc.path).absolutePath==target.absolutePath)true else File(doc.path).renameTo(target)
            if(moved){doc.path=target.absolutePath;doc.category=category;doc.folderId=folderId;doc.size=target.length()}
        }
        selection.clear();moveTarget=null;saveData()
    }

    @Composable private fun RenameDialog(doc:LocalDocument){var name by remember(doc.id){mutableStateOf(doc.name.substringBeforeLast('.'))};AlertDialog(onDismissRequest={renameTarget=null},title={Text("Rename file")},text={OutlinedTextField(name,{name=it},singleLine=true)},confirmButton={TextButton(onClick={if(name.isNotBlank()){val ext=doc.name.substringAfterLast('.',"").let{if(it.isBlank())"" else ".$it"};val newName=name.trim()+ext;val oldFile=File(doc.path);val newFile=File(oldFile.parentFile,newName);if(oldFile.absolutePath!=newFile.absolutePath&&(!newFile.exists()||oldFile.renameTo(newFile))){doc.name=newName;doc.path=newFile.absolutePath};saveData();renameTarget=null}}){Text("Rename")}},dismissButton={TextButton(onClick={renameTarget=null}){Text("Cancel")}})}
    @Composable private fun DetailsDialog(doc:LocalDocument){AlertDialog(onDismissRequest={detailsTarget=null},title={Text("File details")},text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){Text(doc.name,fontWeight=FontWeight.Bold);Text("Type: ${doc.mimeType}");Text("Size: ${formatSize(doc.size)}");Text("Section: ${doc.category}");Text("Location: Local app storage",color=MaterialTheme.colorScheme.onSurfaceVariant)}},confirmButton={TextButton(onClick={detailsTarget=null}){Text("Close")}})}
    @Composable private fun MoveDialog(doc:LocalDocument){val subject=subjects.firstOrNull{it.id==doc.subjectId}?:return;AlertDialog(onDismissRequest={moveTarget=null},title={Text("Move file")},text={Column{listOf("Notes","Syllabus & Exams","Practical").forEach{c->ListItem(headlineContent={Text(c)},modifier=Modifier.clickable{applyMove(c,null)})};folders.filter{it.subjectId==subject.id}.forEach{f->ListItem(headlineContent={Text("📁 ${f.name}")},modifier=Modifier.clickable{applyMove(doc.category,f.id)})}}},confirmButton={TextButton(onClick={moveTarget=null}){Text("Cancel")}})}

    @Composable
    private fun SearchScreen() {
        var q by remember { mutableStateOf("") }
        val results = documents.filter { doc ->
            isDocumentVisible(doc) && (
                q.isBlank() ||
                doc.name.contains(q, ignoreCase = true) ||
                subjects.firstOrNull { subject -> subject.id == doc.subjectId }
                    ?.name?.contains(q, ignoreCase = true) == true
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searchOpen = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = q,
                            onValueChange = { q = it },
                            placeholder = { Text("Search") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }
        ) { pad ->
            if (results.isEmpty()) {
                EmptyState("No results", "Try another file or subject name")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.id }) { doc ->
                        val subjectName = subjects.firstOrNull { it.id == doc.subjectId }?.name ?: "Unknown"
                        ListItem(
                            headlineContent = { Text(doc.name) },
                            supportingContent = { Text("$subjectName • ${doc.category}") },
                            leadingContent = {
                                Icon(
                                    if (doc.isImage) Icons.Default.Image else Icons.Default.Description,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable {
                                if (doc.isImage) openImageGallery(listOf(doc), 0) else openFile(doc)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TrashScreen() {
        val trash = documents.filter { it.trashed }.sortedByDescending { it.deletedAt }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Trash") },
                    navigationIcon = {
                        IconButton(onClick = { trashOpen = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { pad ->
            if (trash.isEmpty()) {
                EmptyState("Trash is empty", "Deleted files will appear here")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(trash, key = { it.id }) { doc ->
                        ListItem(
                            headlineContent = { Text(doc.name) },
                            supportingContent = { Text("${formatSize(doc.size)} • ${doc.category}") },
                            leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        doc.trashed = false
                                        doc.deletedAt = 0L
                                        saveData()
                                    }) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore")
                                    }
                                    IconButton(onClick = { permanentlyDelete(doc) }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete permanently")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    private fun permanentlyDelete(doc:LocalDocument){runCatching{File(doc.path).delete()};documents.remove(doc);saveData()}

    @Composable private fun SettingsScreen(){
        val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->uri?.let{exportBackup(it)}}
        val importBackupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{importBackup(it)}}
        Scaffold(topBar={TopAppBar(title={Text("Settings")},navigationIcon={IconButton(onClick={settingsOpen=false}){Icon(Icons.Default.ArrowBack,null)}})}){pad->LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Theme",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Every theme is unlocked for now.",color=MaterialTheme.colorScheme.onSurfaceVariant)};item{ThemeGrid()};item{AppearanceSelector()};item{
            ListItem(
                headlineContent={Text(if(isPremiumUser)"💎 Premium sharing" else "💎 Premium sharing")},
                supportingContent={Text(if(isPremiumUser)"Watermark control is enabled for your Premium build." else "Free sharing always includes a subtle transparent Liquid Space watermark.")},
                trailingContent={
                    if(isPremiumUser) Switch(shareWatermarkEnabled,{shareWatermarkEnabled=it;saveData()})
                    else Icon(Icons.Default.Lock,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            )
        };item{HorizontalDivider()};item{ListItem(headlineContent={Text("🔒 App lock")},supportingContent={Text("Protect Liquid Space with device biometrics / screen lock")},trailingContent={Switch(locked,{locked=it;saveData();if(it)authenticate()})})};item{ListItem(headlineContent={Text("🙈 Hide private folders")},supportingContent={Text("Hide files assigned to private folders")},trailingContent={Switch(hidePrivateFolders,{hidePrivateFolders=it;saveData()})})};item{ListItem(headlineContent={Text("🗑️ Trash")},supportingContent={Text("Recover deleted files")},modifier=Modifier.clickable{settingsOpen=false;trashOpen=true})};item{ListItem(headlineContent={Text("💾 Export backup")},supportingContent={Text("Create a portable .spacee backup")},modifier=Modifier.clickable{exportLauncher.launch("liquid-space-${System.currentTimeMillis()}.spacee")})};item{ListItem(headlineContent={Text("♻️ Import backup")},supportingContent={Text("Restore your library on this device")},modifier=Modifier.clickable{importBackupLauncher.launch(arrayOf("application/zip","application/octet-stream","*/*"))})};item{ListItem(headlineContent={Text("🔐 Privacy dashboard")},supportingContent={Text("Local storage, lock and privacy status")},modifier=Modifier.clickable{privacyDashboard=true})};item{HorizontalDivider()};item{ListItem(headlineContent={Text("☕ Support Liquid Space")},supportingContent={Text("If Liquid Space helps you, support its development")},leadingContent={BmcCupButton(compact = false, width = 54.dp)},modifier=Modifier.clickable{openSupportPage()})}}}}
    @Composable private fun AppearanceSelector(){
        Text("Appearance",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){AppearanceMode.values().forEach{m->FilterChip(selected=appearanceMode==m,onClick={appearanceMode=m;saveData()},label={Text("${m.emoji} ${m.title}")})}}
    }
    @Composable private fun PrivacyDialog(){
        val total=documents.count{!it.trashed};val trash=documents.count{it.trashed};val bytes=documents.filter{!it.trashed}.sumOf{it.size};val privateCount=folders.count{it.isPrivate}
        AlertDialog(onDismissRequest={privacyDashboard=false},title={Text("Privacy dashboard")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Local-only library",fontWeight=FontWeight.Bold);Text("$total active files • ${formatSize(bytes)}");Text("$trash files in trash • $privateCount private folders");Text(if(locked)"App lock is enabled." else "App lock is disabled.");Text("Liquid Space does not require a cloud account for your documents.",color=MaterialTheme.colorScheme.onSurfaceVariant)}},confirmButton={TextButton(onClick={privacyDashboard=false}){Text("Done")}})
    }
    @Composable private fun ThemeGrid(){
        LazyVerticalGrid(
            columns=GridCells.Fixed(2),
            modifier=Modifier.height(540.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            items(ThemeChoice.values().toList()){t->
                val selected=t==themeChoice
                val preview=paletteFor(t)
                val previewDark=when(appearanceMode){
                    AppearanceMode.LIGHT->false
                    AppearanceMode.DARK->true
                    AppearanceMode.SYSTEM->androidx.compose.foundation.isSystemInDarkTheme()
                }
                val colors=if(previewDark)preview.dark else preview.light
                val style=styleFor(t)
                Card(
                    onClick={themeChoice=t;saveData()},
                    shape=RoundedCornerShape(style.cardRadius.dp),
                    border=if(selected)BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.12f)),
                    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
                    elevation=CardDefaults.cardElevation(defaultElevation=style.cardElevation.dp)
                ){
                    Column(Modifier.padding(10.dp)){
                        // Miniature preview of the complete app, not just a background swatch.
                        Surface(
                            shape=RoundedCornerShape((style.tileRadius+2).dp),
                            color=colors.background,
                            modifier=Modifier.fillMaxWidth().height(112.dp)
                        ){
                            Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(colors.primary.copy(alpha=.18f)),contentAlignment=Alignment.Center){
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(colors.primary))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.width(62.dp).height(7.dp).clip(CircleShape).background(colors.onBackground.copy(alpha=.70f)))
                                    Spacer(Modifier.weight(1f))
                                    Box(Modifier.size(15.dp).clip(CircleShape).background(colors.onBackground.copy(alpha=.22f)))
                                }
                                Surface(shape=RoundedCornerShape(style.controlRadius.dp),color=colors.surfaceVariant.copy(alpha=.8f),modifier=Modifier.fillMaxWidth().height(22.dp)){ }
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    repeat(3){
                                        Surface(
                                            shape=RoundedCornerShape(style.tileRadius.dp),
                                            color=colors.surface,
                                            border=BorderStroke(1.dp,colors.onBackground.copy(alpha=.07f)),
                                            modifier=Modifier.weight(1f).height(23.dp)
                                        ){}
                                    }
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    repeat(2){
                                        Surface(
                                            shape=RoundedCornerShape(style.cardRadius.dp),
                                            color=colors.surface,
                                            modifier=Modifier.weight(1f).height(27.dp)
                                        ){
                                            Box(Modifier.padding(5.dp)){
                                                Box(Modifier.width(34.dp).height(5.dp).clip(CircleShape).background(colors.onSurface.copy(alpha=.35f)))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(t.emoji,fontSize=17.sp)
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)){
                                Text(t.title,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(style.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(selected)Icon(Icons.Default.CheckCircle,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }

    private fun exportBackup(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    val manifest = JSONObject().apply {
                        put("version", 2)
                        put("subjects", JSONArray().apply {
                            subjects.forEach { subject ->
                                put(JSONObject().apply {
                                    put("id", subject.id)
                                    put("name", subject.name)
                                    put("icon", subject.icon)
                                    put("color", subject.color)
                                })
                            }
                        })
                        put("folders", JSONArray().apply {
                            folders.forEach { folder ->
                                put(JSONObject().apply {
                                    put("id", folder.id)
                                    put("subjectId", folder.subjectId)
                                    put("name", folder.name)
                                    put("private", folder.isPrivate)
                                })
                            }
                        })
                        put("documents", JSONArray().apply {
                            documents.filter { !it.trashed }.forEach { doc ->
                                put(JSONObject().apply {
                                    put("id", doc.id)
                                    put("name", doc.name)
                                    put("size", doc.size)
                                    put("subjectId", doc.subjectId)
                                    put("category", doc.category)
                                    put("mimeType", doc.mimeType)
                                    put("favorite", doc.favorite)
                                    put("addedAt", doc.addedAt)
                                    put("lastOpenedAt", doc.lastOpenedAt)
                                    put("folderId", doc.folderId ?: -1L)
                                    put("checksum", doc.checksum)
                                })
                            }
                        })
                    }

                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toString().toByteArray())
                    zip.closeEntry()

                    documents.filter { !it.trashed }.forEach { doc ->
                        val file = File(doc.path)
                        if (file.exists() && file.isFile) {
                            zip.putNextEntry(ZipEntry("files/${doc.id}_${doc.name}"))
                            FileInputStream(file).use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
        }.onFailure { it.printStackTrace() }
    }
    private fun importBackup(uri:Uri){runCatching{val temp=File(cacheDir,"restore_${System.currentTimeMillis()}.zip");contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{input.copyTo(it)}};val tempDir=File(cacheDir,"restore_${System.currentTimeMillis()}").apply{mkdirs()};ZipInputStream(FileInputStream(temp)).use{zin->var e=zin.nextEntry;while(e!=null){val out=File(tempDir,e.name);out.parentFile?.mkdirs();if(!e.isDirectory)FileOutputStream(out).use{zin.copyTo(it)};e=zin.nextEntry}};val meta=JSONObject(File(tempDir,"manifest.json").readText());val arr=meta.getJSONArray("subjects");subjects.clear();for(i in 0 until arr.length()){val o=arr.getJSONObject(i);subjects+=Subject(o.getLong("id"),o.getString("name"),o.optString("icon","📚"),o.optLong("color",0xFF6C63FF))};meta.optJSONArray("folders")?.let{fa->folders.clear();for(i in 0 until fa.length()){val o=fa.getJSONObject(i);folders+=Folder(o.getLong("id"),o.getLong("subjectId"),o.getString("name"),o.optBoolean("private",false))}};val docs=meta.getJSONArray("documents");documents.clear();for(i in 0 until docs.length()){val o=docs.getJSONObject(i);val id=o.getLong("id");val prefix="${id}_";val file=tempDir.listFilesRecursively().firstOrNull{it.isFile&&it.name.startsWith(prefix)}?:continue;val dest=File(filesDir,"documents/${o.getLong("subjectId")}/${o.getString("category").replace("/","_")}").apply{mkdirs()};val out=File(dest,o.getString("name"));file.copyTo(out,true);documents+=LocalDocument(id,o.getString("name"),out.absolutePath,out.length(),o.getLong("subjectId"),o.getString("category"),o.optString("mimeType",mimeFromName(out.name)),o.optBoolean("favorite",false),false,0,o.optLong("addedAt",System.currentTimeMillis()),o.optLong("lastOpenedAt",0L),o.optLong("folderId",-1).takeIf{it>=0},o.optString("checksum",""))};saveData()}.onFailure{it.printStackTrace()}}
    private fun File.listFilesRecursively(): Sequence<File> = sequence {
        listFiles()?.forEach { file ->
            if (file.isDirectory) yieldAll(file.listFilesRecursively()) else yield(file)
        }
    }

    @Composable private fun LockScreen(){Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(82.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(.14f)),contentAlignment=Alignment.Center){Icon(Icons.Default.Lock,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)};Spacer(Modifier.height(18.dp));Text("Liquid Space is locked",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Unlock to open your private library",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(20.dp));Button(onClick={authenticate()}){Icon(Icons.Default.Fingerprint,null);Spacer(Modifier.width(8.dp));Text("Unlock")}}}}
    private fun authenticate(){
        val prompt=androidx.biometric.BiometricPrompt(this,mainExecutor,object:androidx.biometric.BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:androidx.biometric.BiometricPrompt.AuthenticationResult){locked=false;saveData()}
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence){if(errorCode==androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED){locked=false;saveData()}}
        })
        val info=androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Liquid Space").setSubtitle("Use biometrics or your device credential").setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        prompt.authenticate(info)
    }

    private fun openImageGallery(images:List<LocalDocument>,startIndex:Int){if(images.isNotEmpty()){images.getOrNull(startIndex.coerceIn(0,images.lastIndex))?.let{it.lastOpenedAt=System.currentTimeMillis();saveData()};imageGallery=images;imageGalleryStart=startIndex.coerceIn(0,images.lastIndex)}}
    @Composable
    private fun ImageGalleryViewer(
        images: List<LocalDocument>,
        startIndex: Int,
        onDismiss: () -> Unit
    ) {
        if (images.isEmpty()) return

        val pager = rememberPagerState(
            initialPage = startIndex.coerceIn(0, images.lastIndex),
            pageCount = { images.size }
        )

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val doc = images[page]
                        val bitmap by produceState<android.graphics.Bitmap?>(
                            initialValue = null,
                            key1 = doc.path
                        ) {
                            value = withContext(Dispatchers.IO) {
                                createPreviewBitmap(doc, galleryPreviewWidth())
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap!!.asImageBitmap(),
                                    contentDescription = doc.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                FileTypeTile(doc)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .statusBarsPadding()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                images[pager.currentPage].name,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${pager.currentPage + 1} / ${images.size}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { shareFile(images[pager.currentPage]) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
    private fun openSupportPage(){
        val supportIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.buymeacoffee.com/liquidd"))
        runCatching { startActivity(supportIntent) }
    }

    private fun openFile(doc:LocalDocument){doc.lastOpenedAt=System.currentTimeMillis();saveData();val f=File(doc.path);val uri=FileProvider.getUriForFile(this,"${packageName}.provider",f);startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,doc.mimeType);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Open ${doc.name}"))}
    private fun shareFile(doc:LocalDocument){
        val uri=prepareShareFile(doc) ?: return
        val intent=Intent(Intent.ACTION_SEND).apply{
            type=if(doc.isImage) "image/*" else doc.mimeType
            putExtra(Intent.EXTRA_STREAM,uri)
            if (shareWatermarkEnabled) putExtra(Intent.EXTRA_TITLE, "Shared from Liquid Space")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=android.content.ClipData.newRawUri("Liquid Space",uri)
        }
        startActivity(Intent.createChooser(intent,"Share ${doc.name}"))
    }

    /** Creates a branded, temporary copy for outbound sharing. Images receive a visible watermark;
     * documents keep their original bytes and additionally receive a share caption when supported. */
    private fun prepareNormalShareFile(doc:LocalDocument): Uri? {
        val source=File(doc.path)
        if(!source.exists() || !source.isFile) return null
        // "Share normally" deliberately sends the original local file.
        // No watermarking, conversion, or re-encoding is performed.
        return FileProvider.getUriForFile(this,"${packageName}.provider",source)
    }

    private fun prepareShareFile(doc:LocalDocument): Uri? {
        val source=File(doc.path)
        if(!source.exists()) return null
        val watermark=!isPremiumUser || shareWatermarkEnabled
        val file=when {
            watermark && doc.isImage -> createWatermarkedImage(source)
            watermark && doc.mimeType.equals("application/pdf",ignoreCase=true) -> createWatermarkedPdf(source)
            else -> source
        }
        return FileProvider.getUriForFile(this,"${packageName}.provider",file)
    }

    private fun shareableFileForArchive(doc:LocalDocument):File? {
        val source=File(doc.path)
        if(!source.exists()) return null
        val watermark=!isPremiumUser || shareWatermarkEnabled
        return when {
            watermark && doc.isImage -> createWatermarkedImage(source)
            watermark && doc.mimeType.equals("application/pdf",ignoreCase=true) -> createWatermarkedPdf(source)
            else -> source
        }
    }

    private fun createWatermarkedPdf(source:File):File {
        cacheDir.listFiles()?.filter{it.name.startsWith("share_pdf_")}?.forEach{
            if(System.currentTimeMillis()-it.lastModified()>24L*60L*60L*1000L)it.delete()
        }
        val out=File(cacheDir,"share_pdf_${System.currentTimeMillis()}.pdf")
        val renderer=PdfRenderer(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY))
        val pdf=PdfDocument()
        try{
            for(i in 0 until renderer.pageCount){
                val page=renderer.openPage(i)
                val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val info=PdfDocument.PageInfo.Builder(page.width,page.height,i+1).create()
                val outputPage=pdf.startPage(info)
                outputPage.canvas.drawBitmap(bitmap,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG))
                drawPdfWatermark(outputPage.canvas,page.width,page.height)
                pdf.finishPage(outputPage)
                bitmap.recycle()
                page.close()
            }
            FileOutputStream(out).use{pdf.writeTo(it)}
        }finally{
            pdf.close()
            renderer.close()
        }
        return out
    }

    private fun drawPdfWatermark(canvas: android.graphics.Canvas, w: Int, h: Int){
        canvas.save()
        canvas.rotate(-24f, w / 2f, h / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(48, 100, 100, 100)
            textSize = (w * 0.045f).coerceIn(26f, 58f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val text = "Shared from Liquid Space"
        val gapX = (w * 0.72f).coerceAtLeast(320f)
        val gapY = (h * 0.22f).coerceAtLeast(150f)
        var y = -h.toFloat()
        while (y < h * 2f) {
            var x = -w.toFloat()
            while (x < w * 2f) {
                canvas.drawText(text, x, y, paint)
                x += gapX
            }
            y += gapY
        }
        canvas.restore()
    }

    private fun createWatermarkedImage(source:File): File {
        // Keep the temporary share cache small; these files are never part of the user's library.
        cacheDir.listFiles()?.filter { it.name.startsWith("share_") }?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L) it.delete()
        }
        val safeName=source.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"),"_")
        val out=File(cacheDir,"share_${System.currentTimeMillis()}_${safeName}.png")
        val opts=BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888}
        var bitmap=BitmapFactory.decodeFile(source.absolutePath,opts)
        if(bitmap==null) return source
        // Prevent memory spikes on very large camera images while retaining good share quality.
        val maxDim=4096
        if(maxOf(bitmap.width,bitmap.height)>maxDim){
            val scale=maxDim.toFloat()/maxOf(bitmap.width,bitmap.height)
            val resized=Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
            if(resized!==bitmap) bitmap.recycle()
            bitmap=resized
        }
        return try {
            val result=bitmap.copy(Bitmap.Config.ARGB_8888,true)
            val canvas=Canvas(result)
            val density=resources.displayMetrics.density
            val textSize=(34*density).coerceIn(24f,72f)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=AndroidColor.argb(155,255,255,255)
                this.textSize=textSize
                typeface=Typeface.create("sans-serif",Typeface.BOLD)
                setShadowLayer(8f,0f,2f,AndroidColor.BLACK)
            }
            val text="Shared from Liquid Space"
            val sub="Student document manager"
            val subPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=AndroidColor.argb(135,255,255,255)
                this.textSize=textSize*0.52f
                typeface=Typeface.create("sans-serif",Typeface.NORMAL)
                setShadowLayer(6f,0f,2f,AndroidColor.BLACK)
            }
            // Subtle transparent watermark: no opaque banner, just low-alpha branding.
            canvas.save()
            canvas.rotate(-24f,result.width/2f,result.height/2f)
            val gapX=(result.width*0.72f).coerceAtLeast(320f)
            val gapY=(result.height*0.20f).coerceAtLeast(150f)
            var y=-result.height.toFloat()
            while(y<result.height*2f){
                var x=-result.width.toFloat()
                while(x<result.width*2f){
                    canvas.drawText(text,x,y,paint)
                    canvas.drawText(sub,x,y+textSize*0.72f,subPaint)
                    x+=gapX
                }
                y+=gapY
            }
            canvas.restore()
            FileOutputStream(out).use{result.compress(Bitmap.CompressFormat.PNG,100,it)}
            result.recycle()
            out
        } finally {
            bitmap.recycle()
        }
    }
    // Bitmap LruCache is measured in bytes, not entry count. The old cache used
    // 8M as an entry count, which could retain an enormous amount of RAM.
    private val previewCache by lazy {
        // Keep the cache capacity explicitly Int because Android's LruCache uses
        // an Int size unit. The cache is intentionally conservative for older
        // and low-memory devices.
        val maxBytes = if (getSystemService(ActivityManager::class.java)?.isLowRamDevice == true) {
            6 * 1024 * 1024
        } else {
            12 * 1024 * 1024
        }
        object : LruCache<String, android.graphics.Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
                value.allocationByteCount
        }
    }

    private fun galleryPreviewWidth(): Int =
        if (getSystemService(ActivityManager::class.java)?.isLowRamDevice == true) 720 else 1080

    private fun createPreviewBitmap(doc:LocalDocument, targetWidth:Int):android.graphics.Bitmap? {
        val key="${doc.path}|$targetWidth|${doc.size}"
        previewCache.get(key)?.let{return it}
        val bitmap=runCatching {
            when {
                doc.isImage -> decodeSampledImage(File(doc.path), targetWidth)
                doc.mimeType=="application/pdf" || doc.name.endsWith(".pdf",true) -> renderPdfPreview(File(doc.path), targetWidth)
                else -> null
            }
        }.getOrNull()
        if(bitmap!=null) previewCache.put(key, bitmap)
        return bitmap
    }

    private fun decodeSampledImage(file:File, targetWidth:Int):android.graphics.Bitmap? {
        val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0) return null
        var sample=1
        while(bounds.outWidth/(sample*2)>=targetWidth) sample*=2
        val opts=android.graphics.BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=android.graphics.Bitmap.Config.RGB_565}
        return BitmapFactory.decodeFile(file.absolutePath,opts)
    }

    private fun renderPdfPreview(file:File,targetWidth:Int):android.graphics.Bitmap? {
        val pd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(pd).use { renderer ->
                if(renderer.pageCount==0) return null
                renderer.openPage(0).use { page ->
                    val w=targetWidth
                    val h=(w.toFloat()*page.height/page.width).toInt().coerceAtLeast(targetWidth)
                    android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(AndroidColor.WHITE)
                        page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        } finally { pd.close() }
    }

    private fun formatSize(b:Long)=when{b<1024->"$b B";b<1024*1024->"${b/1024} KB";else->String.format("%.1f MB",b/1024.0/1024.0)}
}
