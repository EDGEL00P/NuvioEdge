@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components.ticker

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.ThemeSelection
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.model.resolveCustomThemeColors
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.SportsTickerRepository
import com.nuvio.tv.data.sports.TickerGame
import com.nuvio.tv.data.sports.TickerKind
import com.nuvio.tv.data.sports.withoutScores
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class SportsPipState(
    val games: List<TickerGame> = emptyList(),
    val loading: Boolean = true,
    val unavailable: Boolean = false
)

@AndroidEntryPoint
class SportsScorePipActivity : ComponentActivity() {
    @Inject lateinit var repository: SportsTickerRepository
    @Inject lateinit var themeDataStore: ThemeDataStore
    @Inject lateinit var memberAccessRepository: MemberAccessRepository

    private val scoreState = MutableStateFlow(SportsPipState())
    private var attemptedEntry = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSupported(this)) {
            finish()
            return
        }
        attemptedEntry = savedInstanceState?.getBoolean("pip_entry_attempted") ?: false
        val hideScores = repository.hideScores.stateIn(lifecycleScope, SharingStarted.WhileSubscribed(5_000), true)
        val themeSelection = combine(themeDataStore.themeSelection, memberAccessRepository.access) { selection, access ->
            selection.copy(
                theme = resolveAppTheme(selection.theme, access.entitlements),
                customColors = resolveCustomThemeColors(selection.customColors, access.tier)
            )
        }
        val selectedFont = themeDataStore.selectedFont
        val amoledMode = themeDataStore.amoledMode
        val amoledSurfacesMode = themeDataStore.amoledSurfacesMode
        setContent {
            val selection by themeSelection.collectAsStateWithLifecycle(initialValue = ThemeSelection())
            val font by selectedFont.collectAsStateWithLifecycle(initialValue = AppFont.INTER)
            val amoled by amoledMode.collectAsStateWithLifecycle(initialValue = false)
            val amoledSurfaces by amoledSurfacesMode.collectAsStateWithLifecycle(initialValue = false)
            NuvioTheme(
                appTheme = selection.theme ?: AppTheme.WHITE,
                customThemeColors = selection.customColors,
                appFont = font,
                amoledMode = amoled,
                amoledSurfacesMode = amoledSurfaces
            ) {
                val state by scoreState.collectAsStateWithLifecycle()
                val hidden by hideScores.collectAsStateWithLifecycle()
                SportsPipContent(if (hidden) state.copy(games = state.games.map { it.withoutScores() }) else state)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        val games = repository.fetchGames(onFirstGames = { early ->
                            scoreState.update { it.copy(games = early, loading = false, unavailable = false) }
                        })
                        scoreState.value = SportsPipState(games = games, loading = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        scoreState.update { it.copy(loading = false, unavailable = true) }
                    }
                    delay(60_000L)
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (attemptedEntry || !isSupported(this)) return
        attemptedEntry = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val entered = runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .setTitle(getString(R.string.sports_pip_title))
                        .setSubtitle(getString(R.string.scores_title))
                        .setAutoEnterEnabled(false)
                        .build()
                )
            }.getOrDefault(false)
            if (!entered) {
                scoreState.update { it.copy(loading = false, unavailable = true) }
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pip_entry_attempted", attemptedEntry)
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        fun open(context: Context): Boolean {
            if (!isSupported(context)) return false
            return runCatching {
                context.startActivity(Intent(context, SportsScorePipActivity::class.java))
                true
            }.getOrDefault(false)
        }
    }
}

@Composable
private fun SportsPipContent(state: SportsPipState) {
    Column(
        modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.scores_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (state.games.isEmpty()) {
            Text(
                text = stringResource(
                    when {
                        state.loading -> R.string.sports_pip_loading
                        state.unavailable -> R.string.sports_pip_unavailable
                        else -> R.string.scores_empty
                    }
                ),
                fontSize = 14.sp,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            state.games.take(3).forEach { game ->
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    val score = if (game.kind == TickerKind.LEADERBOARD) {
                        game.leaders.firstOrNull()?.let { leader ->
                            listOfNotNull(leader.position, leader.name, leader.score).joinToString(" ")
                        } ?: game.event.orEmpty()
                    } else {
                        val away = listOfNotNull(game.away.abbreviation, game.away.score.takeIf { game.state != GameState.PRE }).joinToString(" ")
                        val home = listOfNotNull(game.home.abbreviation, game.home.score.takeIf { game.state != GameState.PRE }).joinToString(" ")
                        "$away / $home"
                    }
                    Text(score, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${game.league}  ${game.status}", fontSize = 11.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}