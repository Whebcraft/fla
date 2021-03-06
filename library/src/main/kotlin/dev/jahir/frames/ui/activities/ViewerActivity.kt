package dev.jahir.frames.ui.activities

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.palette.graphics.Palette
import com.fondesa.kpermissions.PermissionStatus
import dev.jahir.frames.R
import dev.jahir.frames.data.models.Wallpaper
import dev.jahir.frames.extensions.*
import dev.jahir.frames.ui.activities.base.BaseFavoritesConnectedActivity
import dev.jahir.frames.ui.fragments.WallpapersFragment
import dev.jahir.frames.ui.fragments.viewer.ApplierDialog
import dev.jahir.frames.ui.fragments.viewer.DetailsFragment
import dev.jahir.frames.ui.fragments.viewer.DownloadToApplyDialog
import dev.jahir.frames.ui.fragments.viewer.SetAsOptionsDialog
import dev.jahir.frames.ui.widgets.FramesPhotoView
import dev.jahir.frames.utils.Prefs
import dev.jahir.frames.utils.tint

open class ViewerActivity : BaseFavoritesConnectedActivity<Prefs>() {

    override val prefs: Prefs by lazy { Prefs(this) }

    private val toolbar: Toolbar? by findView(R.id.toolbar)
    private val image: AppCompatImageView? by findView(R.id.wallpaper)

    private var transitioned: Boolean = false
    private var closing: Boolean = false
    private var currentWallPosition: Int = 0
    private var favoritesModified: Boolean = false
    private var isInFavorites: Boolean = false
        set(value) {
            field = value
            bottomNavigation?.setSelectedItemId(if (value) R.id.favorites else R.id.details, false)
        }

    private val detailsFragment: DetailsFragment by lazy {
        DetailsFragment.create(shouldShowPaletteDetails = shouldShowWallpapersPalette())
    }

    private var downloadBlockedDialog: AlertDialog? = null
    private var applierDialog: DialogFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusBarLight = false
        navigationBarLight = false
        setContentView(R.layout.activity_viewer)

        supportPostponeEnterTransition()

        currentWallPosition = intent?.extras?.getInt(CURRENT_WALL_POSITION, 0) ?: 0

        val wallpaper =
            intent?.extras?.getParcelable<Wallpaper?>(WallpapersFragment.WALLPAPER_EXTRA)

        if (wallpaper == null) {
            finish()
            return
        }
        if (wallpaper.downloadable == false || !shouldShowDownloadOption())
            bottomNavigation?.removeItem(R.id.download)

        initFetch(wallpaper)
        detailsFragment.wallpaper = wallpaper

        findViewById<View?>(R.id.toolbar_title)?.let {
            (it as? TextView)?.text = wallpaper.name
            ViewCompat.setTransitionName(
                it,
                wallpaper.buildTitleTransitionName(currentWallPosition)
            )
        }
        findViewById<View?>(R.id.toolbar_subtitle)?.let {
            (it as? TextView)?.text = wallpaper.author
            ViewCompat.setTransitionName(
                it,
                wallpaper.buildAuthorTransitionName(currentWallPosition)
            )
        }
        image?.let {
            ViewCompat.setTransitionName(
                it,
                wallpaper.buildImageTransitionName(currentWallPosition)
            )
        }

        setSupportActionBar(toolbar)
        supportActionBar?.let {
            it.setHomeButtonEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            it.setDisplayShowHomeEnabled(true)
        }
        initWindow()
        toolbar?.tint(ContextCompat.getColor(this, R.color.white))

        (image as? FramesPhotoView)?.setOnPhotoTapListener { _, _, _ -> toggleSystemUI() }
            ?: image?.setOnClickListener { toggleSystemUI() }
        image?.loadFramesPic(wallpaper.url, wallpaper.thumbnail, null, true) { generatePalette(it) }

        supportStartPostponedEnterTransition()

        isInFavorites =
            intent?.extras?.getBoolean(WallpapersFragment.WALLPAPER_IN_FAVS_EXTRA, false)
                ?: wallpaper.isInFavorites

        wallpapersViewModel.observeFavorites(this) {
            this.isInFavorites = it.any { wall -> wall.url == wallpaper.url }
        }

        bottomNavigation?.setSelectedItemId(
            if (isInFavorites) R.id.favorites else R.id.details,
            false
        )
        wallpapersViewModel.loadData(this)

        bottomNavigation?.setOnNavigationItemSelectedListener {
            handleNavigationItemSelected(it.itemId, wallpaper)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CURRENT_WALL_POSITION, currentWallPosition)
        outState.putBoolean(CLOSING_KEY, closing)
        outState.putBoolean(TRANSITIONED_KEY, transitioned)
        outState.putBoolean(IS_IN_FAVORITES_KEY, isInFavorites)
        outState.putBoolean(FAVORITES_MODIFIED, favoritesModified)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        this.currentWallPosition = savedInstanceState.getInt(CURRENT_WALL_POSITION, 0)
        this.closing = savedInstanceState.getBoolean(CLOSING_KEY, false)
        this.transitioned = savedInstanceState.getBoolean(TRANSITIONED_KEY, false)
        this.isInFavorites = savedInstanceState.getBoolean(IS_IN_FAVORITES_KEY, false)
        this.favoritesModified = savedInstanceState.getBoolean(FAVORITES_MODIFIED, false)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) supportFinishAfterTransition()
        return super.onOptionsItemSelected(item)
    }

    override fun onFinish() {
        super.onFinish()
        setResult(
            if (favoritesModified) FAVORITES_MODIFIED_RESULT
            else FAVORITES_NOT_MODIFIED_RESULT,
            Intent().apply {
                putExtra(FAVORITES_MODIFIED, favoritesModified)
            }
        )
    }

    private fun dismissApplierDialog() {
        try {
            applierDialog?.dismiss()
        } catch (e: Exception) {
        }
        applierDialog = null
    }

    private fun dismissDownloadBlockedDialog() {
        try {
            downloadBlockedDialog?.dismiss()
        } catch (e: Exception) {
        }
        downloadBlockedDialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        (image as? FramesPhotoView)?.setScale(1F, true)
        dismissApplierDialog()
        dismissDownloadBlockedDialog()
        try {
            val bmp = (image?.drawable as? BitmapDrawable?)?.bitmap
            if (bmp?.isRecycled == false) bmp.recycle()
        } catch (e: Exception) {
        }
        try {
            image?.setImageDrawable(null)
        } catch (e: Exception) {
        }
    }

    private fun generatePalette(drawable: Drawable?) {
        supportStartPostponedEnterTransition()
        findViewById<View?>(R.id.loading)?.gone()
        if (!shouldShowWallpapersPalette()) {
            setBackgroundColor()
            return
        }
        drawable?.asBitmap()?.let { bitmap ->
            Palette.from(bitmap)
                .maximumColorCount(MAX_FRAMES_PALETTE_COLORS * 2)
                .generate {
                    setBackgroundColor(it?.bestSwatch?.rgb ?: 0)
                    detailsFragment.palette = it
                }
        } ?: { setBackgroundColor() }()
    }

    private fun setBackgroundColor(@ColorInt color: Int = 0) {
        findViewById<View?>(R.id.activity_root_view)?.setBackgroundColor(color)
    }

    private fun initWindow() {
        if (Build.VERSION.SDK_INT >= 21) {
            window.decorView.systemUiVisibility =
                SYSTEM_UI_FLAG_LAYOUT_STABLE or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

            val params: WindowManager.LayoutParams = window.attributes
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
            window.attributes = params

            appbar?.let { appbar ->
                ViewCompat.setOnApplyWindowInsetsListener(appbar) { _, insets ->
                    appbar.setMarginTop(insets.systemWindowInsetTop)
                    insets
                }
            }

            bottomNavigation?.let { bottomNavigation ->
                ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation) { _, insets ->
                    bottomNavigation.setMarginBottom(insets.systemWindowInsetBottom)
                    insets
                }
            }

            window.statusBarColor = ContextCompat.getColor(this, R.color.viewer_bars_colors)
            window.navigationBarColor = ContextCompat.getColor(this, R.color.viewer_bars_colors)
        }
    }

    open fun handleNavigationItemSelected(itemId: Int, wallpaper: Wallpaper?): Boolean {
        wallpaper ?: return false
        when (itemId) {
            R.id.details -> detailsFragment.show(this, "DETAILS_FRAG")
            R.id.download -> checkForDownload()
            R.id.apply -> applyWallpaper(wallpaper)
            R.id.favorites -> {
                if (canModifyFavorites()) {
                    this.favoritesModified = true
                    if (isInFavorites) removeFromFavorites(wallpaper)
                    else addToFavorites(wallpaper)
                } else onFavoritesLocked()
            }
        }
        return false
    }

    private fun checkForDownload() {
        if (!shouldShowDownloadOption()) return
        val actuallyComplies = if (intent?.getBooleanExtra(LICENSE_CHECK_ENABLED, false) == true)
            compliesWithMinTime(MIN_TIME) || resources.getBoolean(R.bool.allow_immediate_downloads)
        else true
        if (actuallyComplies) {
            requestStoragePermission()
        } else {
            val elapsedTime = System.currentTimeMillis() - firstInstallTime
            val timeLeft = MIN_TIME - elapsedTime
            val timeLeftText = timeLeft.toReadableTime()

            dismissDownloadBlockedDialog()
            downloadBlockedDialog = mdDialog {
                title(R.string.prevent_download_title)
                message(getString(R.string.prevent_download_content, timeLeftText))
                positiveButton(android.R.string.ok) { it.dismiss() }
            }
            downloadBlockedDialog?.show()
        }
    }

    override fun internalOnPermissionsGranted(result: List<PermissionStatus>) {
        super.internalOnPermissionsGranted(result)
        startDownload()
    }

    private fun applyWallpaper(wallpaper: Wallpaper?) {
        wallpaper ?: return
        dismissApplierDialog()
        applierDialog = SetAsOptionsDialog.create(wallpaper)
        applierDialog?.show(supportFragmentManager, SetAsOptionsDialog.TAG)
    }

    internal fun showApplierDialog(wallpaper: Wallpaper?, selectedOption: Int = -1) {
        wallpaper ?: return
        dismissApplierDialog()
        applierDialog = ApplierDialog.create(wallpaper, selectedOption)
        applierDialog?.show(supportFragmentManager, DownloadToApplyDialog.TAG)
    }

    private fun shouldShowWallpapersPalette(): Boolean = try {
        resources.getBoolean(R.bool.show_wallpaper_palette_details)
    } catch (e: Exception) {
        true
    }

    open fun shouldShowDownloadOption() = true
    override val shouldChangeStatusBarLightStatus: Boolean = false
    override val shouldChangeNavigationBarLightStatus: Boolean = false

    override fun canToggleSystemUIVisibility(): Boolean =
        intent?.getBooleanExtra(CAN_TOGGLE_SYSTEMUI_VISIBILITY_KEY, true) ?: true

    companion object {
        internal const val MIN_TIME: Long = 3 * 60 * 60000
        internal const val REQUEST_CODE = 10
        internal const val FAVORITES_MODIFIED = "favorites_modified"
        internal const val FAVORITES_MODIFIED_RESULT = 1
        internal const val FAVORITES_NOT_MODIFIED_RESULT = 0
        internal const val CURRENT_WALL_POSITION = "curr_wall_pos"
        internal const val LICENSE_CHECK_ENABLED = "license_check_enabled"
        internal const val CAN_TOGGLE_SYSTEMUI_VISIBILITY_KEY = "can_toggle_visibility"
        private const val CLOSING_KEY = "closing"
        private const val TRANSITIONED_KEY = "transitioned"
        private const val IS_IN_FAVORITES_KEY = "is_in_favorites"
    }
}