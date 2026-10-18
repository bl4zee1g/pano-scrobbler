package com.arn.scrobble.discordrpc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arn.scrobble.BuildKonfig
import com.arn.scrobble.api.AccountType
import com.arn.scrobble.icons.Icons
import com.arn.scrobble.icons.ResetSettings
import com.arn.scrobble.pref.DropdownPref
import com.arn.scrobble.pref.MainPrefs
import com.arn.scrobble.pref.SliderPref
import com.arn.scrobble.pref.SwitchPref
import com.arn.scrobble.ui.HighlighterOutputTransformation
import com.arn.scrobble.ui.PanoOutlinedTextField
import com.arn.scrobble.utils.PlatformStuff
import com.arn.scrobble.utils.Stuff
import com.arn.scrobble.utils.Stuff.collectAsStateWithInitialValue
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.album_art
import pano_scrobbler.composeapp.generated.resources.album_art_now_playing
import pano_scrobbler.composeapp.generated.resources.album_art_now_playing_desc
import pano_scrobbler.composeapp.generated.resources.appwidget_show
import pano_scrobbler.composeapp.generated.resources.available_placeholders
import pano_scrobbler.composeapp.generated.resources.button_url
import pano_scrobbler.composeapp.generated.resources.discord_app_name
import pano_scrobbler.composeapp.generated.resources.discord_button_n
import pano_scrobbler.composeapp.generated.resources.discord_button_text
import pano_scrobbler.composeapp.generated.resources.discord_compact_view_line
import pano_scrobbler.composeapp.generated.resources.enable
import pano_scrobbler.composeapp.generated.resources.hide
import pano_scrobbler.composeapp.generated.resources.lastfm
import pano_scrobbler.composeapp.generated.resources.librefm
import pano_scrobbler.composeapp.generated.resources.line_n
import pano_scrobbler.composeapp.generated.resources.listenbrainz
import pano_scrobbler.composeapp.generated.resources.loved
import pano_scrobbler.composeapp.generated.resources.profile
import pano_scrobbler.composeapp.generated.resources.reset
import pano_scrobbler.composeapp.generated.resources.show_paused_for
import pano_scrobbler.composeapp.generated.resources.show_track_url


@Composable
fun DiscordRpcScreen(
    modifier: Modifier = Modifier,
) {
    val settings by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.discordRpc }
    val defaultSettings = remember { MainPrefs.DiscordRpcPrefs() }
    val line1Format = rememberTextFieldState(settings.line1Format)
    val line2Format = rememberTextFieldState(settings.line2Format)
    val line3Format = rememberTextFieldState(settings.line3Format)
    val nameFormat = rememberTextFieldState(settings.nameFormat)
    val button1Label = rememberTextFieldState(settings.button1Label)
    val button1Url = rememberTextFieldState(settings.button1Url)
    val button2Label = rememberTextFieldState(settings.button2Label)
    val button2Url = rememberTextFieldState(settings.button2Url)
    val isDesktop = PlatformStuff.isDesktop
    val accountTypes by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.scrobbleAccounts.map { account -> account.type }.toSet()
    }
    val buttonType by remember(settings.buttonType) {
        mutableStateOf(
            MainPrefs.DiscordRpcPrefs.ButtonType.entries.find { it.name == settings.buttonType }
                ?: MainPrefs.DiscordRpcPrefs.ButtonType.PANO_SCROBBLER
        )
    }
    val line by remember(settings.statusLine) {
        mutableStateOf(
            when (settings.statusLine) {
                1 -> MainPrefs.DiscordRpcPrefs.Line.Line1
                2 -> MainPrefs.DiscordRpcPrefs.Line.Line2
                else -> MainPrefs.DiscordRpcPrefs.Line.None
            }
        )
    }

    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val outputTransformation = remember {
        HighlighterOutputTransformation(
            stringsToHighlight = DiscordRpcPlaceholder.entries.map { "\$" + it.name },
            highlightColor = tertiaryColor
        )
    }

    // the text fields are saved as they are edited, so that the changes apply right away
    val saveTexts: () -> Unit = {
        val texts = listOf(
            line1Format, line2Format, line3Format, nameFormat,
            button1Label, button1Url, button2Label, button2Url,
        ).map { it.text.trim().toString() }

        Stuff.appScope.launch {
            PlatformStuff.mainPrefs.updateData {
                it.copy(
                    discordRpc = it.discordRpc.copy(
                        line1Format = texts[0].ifEmpty { defaultSettings.line1Format },
                        line2Format = texts[1].ifEmpty { defaultSettings.line2Format },
                        line3Format = texts[2], // line 3 can be empty
                        nameFormat = texts[3], // name can be empty
                        // empty buttons are not shown
                        button1Label = texts[4],
                        button1Url = texts[5],
                        button2Label = texts[6],
                        button2Url = texts[7],
                    )
                )
            }
        }
    }
    val currentSaveTexts by rememberUpdatedState(saveTexts)

    LaunchedEffect(Unit) {
        snapshotFlow {
            listOf(
                line1Format, line2Format, line3Format, nameFormat,
                button1Label, button1Url, button2Label, button2Url,
            ).map { it.text.toString() }
        }
            .drop(1) // the initial values
            .debounce(500.milliseconds)
            .collect { currentSaveTexts() }
    }

    DisposableEffect(Unit) {
        onDispose { currentSaveTexts() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        DiscordAccountPref()

        SwitchPref(
            text = stringResource(Res.string.enable),
            value = settings.enabled,
            // allow turning it off even after logging out
            enabled = rememberDiscordLoggedIn() || settings.enabled,
            copyToSave = {
                copy(
                    discordRpc = settings.copy(enabled = it)
                )
            }
        )

        SwitchPref(
            text = stringResource(Res.string.album_art),
            value = settings.albumArt,
            enabled = settings.enabled,
            copyToSave = {
                copy(
                    discordRpc = settings.copy(albumArt = it)
                )
            }
        )

        SwitchPref(
            text = stringResource(Res.string.album_art_now_playing),
            summary = stringResource(Res.string.album_art_now_playing_desc),
            value = settings.albumArtFromNowPlaying,
            enabled = settings.enabled && settings.albumArt,
            copyToSave = {
                copy(
                    discordRpc = settings.copy(albumArtFromNowPlaying = it)
                )
            }
        )

        if (isDesktop) {
            SwitchPref(
                text = stringResource(Res.string.show_track_url),
                value = settings.detailsUrl,
                enabled = settings.enabled,
                copyToSave = {
                    copy(
                        discordRpc = settings.copy(detailsUrl = it)
                    )
                }
            )

            SwitchPref(
                text = stringResource(Res.string.appwidget_show) + ": " + stringResource(Res.string.loved),
                value = settings.lovedState,
                enabled = settings.enabled,
                copyToSave = {
                    copy(
                        discordRpc = settings.copy(lovedState = it)
                    )
                }
            )
        }

        Text(
            outputTransformation.highlightToAnnotatedString(
                stringResource(
                    Res.string.available_placeholders,
                    DiscordRpcPlaceholder.entries.joinToString { "\$" + it.name }
                )),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        PanoOutlinedTextField(
            line1Format,
            label = { Text(stringResource(Res.string.line_n, 1)) },
            outputTransformation = outputTransformation,
            isError = line1Format.text.trim().isEmpty(),
            trailingIcon = {
                IconButton(
                    shapes = IconButtonDefaults.shapes(),
                    enabled = line1Format.text != defaultSettings.line1Format,
                    onClick = {
                        line1Format.setTextAndPlaceCursorAtEnd(defaultSettings.line1Format)
                    }
                ) {
                    Icon(
                        imageVector = Icons.ResetSettings,
                        contentDescription = stringResource(Res.string.reset)
                    )
                }
            },
            enabled = settings.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        PanoOutlinedTextField(
            line2Format,
            label = { Text(stringResource(Res.string.line_n, 2)) },
            outputTransformation = outputTransformation,
            isError = line2Format.text.trim().isEmpty(),
            trailingIcon = {
                IconButton(
                    shapes = IconButtonDefaults.shapes(),
                    enabled = line2Format.text != defaultSettings.line2Format,
                    onClick = {
                        line2Format.setTextAndPlaceCursorAtEnd(defaultSettings.line2Format)
                    }
                ) {
                    Icon(
                        imageVector = Icons.ResetSettings,
                        contentDescription = stringResource(Res.string.reset)
                    )
                }
            },
            enabled = settings.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        PanoOutlinedTextField(
            line3Format,
            label = { Text(stringResource(Res.string.line_n, 3)) },
            outputTransformation = outputTransformation,
            trailingIcon = {
                IconButton(
                    shapes = IconButtonDefaults.shapes(),
                    enabled = line3Format.text != defaultSettings.line3Format,
                    onClick = {
                        line3Format.setTextAndPlaceCursorAtEnd(defaultSettings.line3Format)
                    }
                ) {
                    Icon(
                        imageVector = Icons.ResetSettings,
                        contentDescription = stringResource(Res.string.reset)
                    )
                }
            },
            enabled = settings.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        PanoOutlinedTextField(
            nameFormat,
            label = { Text(stringResource(Res.string.discord_app_name)) },
            outputTransformation = outputTransformation,
            trailingIcon = {
                IconButton(
                    shapes = IconButtonDefaults.shapes(),
                    enabled = nameFormat.text != defaultSettings.nameFormat,
                    onClick = {
                        nameFormat.setTextAndPlaceCursorAtEnd(defaultSettings.nameFormat)
                    }
                ) {
                    Icon(
                        imageVector = Icons.ResetSettings,
                        contentDescription = stringResource(Res.string.reset)
                    )
                }
            },
            enabled = settings.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        // android uses the app name for it
        if (isDesktop) {
            DropdownPref(
                text = stringResource(Res.string.discord_compact_view_line),
                selectedValue = line,
                values = MainPrefs.DiscordRpcPrefs.Line.entries,
                toLabel = {
                    when (it) {
                        MainPrefs.DiscordRpcPrefs.Line.Line1 -> stringResource(Res.string.line_n, 1)
                        MainPrefs.DiscordRpcPrefs.Line.Line2 -> stringResource(Res.string.line_n, 2)
                        MainPrefs.DiscordRpcPrefs.Line.None -> stringResource(Res.string.discord_app_name)
                    }
                },
                copyToSave = {
                    copy(
                        discordRpc = settings.copy(
                            statusLine = it.ordinal
                        )
                    )
                },
                enabled = settings.enabled,
            )
        }

        if (isDesktop) {
            DropdownPref(
                text = stringResource(Res.string.button_url),
                selectedValue = buttonType,
                // only the accounts that are logged in
                values = MainPrefs.DiscordRpcPrefs.ButtonType.entries.filter {
                    when (it) {
                        MainPrefs.DiscordRpcPrefs.ButtonType.LASTFM_PROFILE -> AccountType.LASTFM in accountTypes
                        MainPrefs.DiscordRpcPrefs.ButtonType.LISTENBRAINZ_PROFILE -> AccountType.LISTENBRAINZ in accountTypes
                        MainPrefs.DiscordRpcPrefs.ButtonType.LIBREFM_PROFILE -> AccountType.LIBREFM in accountTypes
                        else -> true
                    } || it == buttonType
                },
                toLabel = {
                    when (it) {
                        MainPrefs.DiscordRpcPrefs.ButtonType.PANO_SCROBBLER -> BuildKonfig.APP_NAME
                        MainPrefs.DiscordRpcPrefs.ButtonType.LASTFM_PROFILE ->
                            stringResource(Res.string.lastfm) + " " + stringResource(Res.string.profile)

                        MainPrefs.DiscordRpcPrefs.ButtonType.LISTENBRAINZ_PROFILE ->
                            stringResource(Res.string.listenbrainz) + " " + stringResource(Res.string.profile)

                        MainPrefs.DiscordRpcPrefs.ButtonType.LIBREFM_PROFILE ->
                            stringResource(Res.string.librefm) + " " + stringResource(Res.string.profile)

                        MainPrefs.DiscordRpcPrefs.ButtonType.NONE ->
                            stringResource(Res.string.hide)
                    }
                },
                copyToSave = {
                    copy(
                        discordRpc = settings.copy(
                            buttonType = it.name
                        )
                    )
                },
                enabled = settings.enabled,
            )
        } else {
            DiscordButtonPref(
                index = 1,
                enabled = settings.enabled,
                buttonEnabled = settings.button1Enabled,
                onButtonEnabledChange = { copy(discordRpc = settings.copy(button1Enabled = it)) },
                label = button1Label,
                url = button1Url,
                outputTransformation = outputTransformation,
            )

            DiscordButtonPref(
                index = 2,
                enabled = settings.enabled,
                buttonEnabled = settings.button2Enabled,
                onButtonEnabledChange = { copy(discordRpc = settings.copy(button2Enabled = it)) },
                label = button2Label,
                url = button2Url,
                outputTransformation = outputTransformation,
            )
        }

        SliderPref(
            text = stringResource(Res.string.show_paused_for),
            value = settings.showPausedForSecs.toFloat(),
            enabled = settings.enabled,
            min = 0,
            max = 600,
            default = defaultSettings.showPausedForSecs,
            increments = 10,
            stringRepresentation = { Stuff.humanReadableDuration(it * 1000L) },
            copyToSave = {
                copy(
                    discordRpc = settings.copy(showPausedForSecs = it)
                )
            },
        )
    }
}
@Composable
private fun DiscordButtonPref(
    index: Int,
    enabled: Boolean,
    buttonEnabled: Boolean,
    onButtonEnabledChange: MainPrefs.(Boolean) -> MainPrefs,
    label: TextFieldState,
    url: TextFieldState,
    outputTransformation: HighlighterOutputTransformation,
) {
    SwitchPref(
        text = stringResource(Res.string.discord_button_n, index),
        value = buttonEnabled,
        enabled = enabled,
        copyToSave = { onButtonEnabledChange(it) }
    )

    PanoOutlinedTextField(
        label,
        label = { Text(stringResource(Res.string.discord_button_text)) },
        outputTransformation = outputTransformation,
        enabled = enabled && buttonEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )

    PanoOutlinedTextField(
        url,
        label = { Text(stringResource(Res.string.button_url)) },
        outputTransformation = outputTransformation,
        enabled = enabled && buttonEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )
}
