/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.*
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.withIpVersionPreference
import app.hush.music.ui.component.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import kotlinx.coroutines.withContext
import app.hush.music.utils.ProxyUtils
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InternetSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (dnsOverHttpsEnabled, onDnsOverHttpsEnabledChange) = rememberPreference(key = EnableDnsOverHttpsKey, defaultValue = false)
    val (dnsProvider, onDnsProviderChange) = rememberPreference(key = DnsOverHttpsProviderKey, defaultValue = "Cloudflare")
    val (customDnsUrl, onCustomDnsUrlChange) = rememberPreference(key = stringPreferencesKey("customDnsUrl"), defaultValue = "https://")
    val (proxyEnabled, onProxyEnabledChange) = rememberPreference(key = ProxyEnabledKey, defaultValue = false)
    val (proxyType, onProxyTypeChange) = rememberEnumPreference(key = ProxyTypeKey, defaultValue = Proxy.Type.HTTP)
    val (proxyHost, onProxyHostChange) = rememberPreference(key = ProxyHostKey, defaultValue = "")
    val (proxyPort, onProxyPortChange) = rememberPreference(key = ProxyPortKey, defaultValue = 8080)
    val (proxyUsername, onProxyUsernameChange) = rememberPreference(key = ProxyUsernameKey, defaultValue = "")
    val (proxyPassword, onProxyPasswordChange) = rememberPreference(key = ProxyPasswordKey, defaultValue = "")
    val (streamBypassProxy, onStreamBypassProxyChange) = rememberPreference(key = StreamBypassProxyKey, defaultValue = false)

    val (ipRotationEnabled, onIpRotationEnabledChange) = rememberPreference(key = IpRotationEnabledKey, defaultValue = false)
    var loadingIpRotation by remember { mutableStateOf(false) }
    var refreshingIpRotation by remember { mutableStateOf(false) }
    val activeProxyCount by YouTube.ipRotationActiveCount.collectAsStateWithLifecycle()

    var testingProxy by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testingDns by remember { mutableStateOf(false) }
    var dnsTestResult by remember { mutableStateOf<String?>(null) }

    val dnsProviders = remember { listOf("Cloudflare", "Google", "AdGuard", "Quad9", "Custom") }
    val proxyTypes = remember { listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS) }
    val ipRotationDescription =
        when {
            loadingIpRotation -> stringResource(R.string.ip_rotation_loading)
            refreshingIpRotation -> stringResource(R.string.ip_rotation_refreshing)
            ipRotationEnabled -> stringResource(R.string.ip_rotation_active_proxies, activeProxyCount)
            else -> stringResource(R.string.ip_rotation_desc)
        }

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ).nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
        ) {
            Spacer(
                Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                ),
            )

        PreferenceGroup(title = stringResource(R.string.dns_over_https)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.dns_over_https)) },
                    description = stringResource(R.string.dns_over_https_desc),
                    icon = { Icon(painterResource(R.drawable.security), null) },
                    checked = dnsOverHttpsEnabled,
                    onCheckedChange = onDnsOverHttpsEnabledChange,
                )
            }

            item(visible = dnsOverHttpsEnabled) {
                ListPreference(
                    title = { Text(stringResource(R.string.dns_provider)) },
                    icon = { Icon(painterResource(R.drawable.website), null) },
                    selectedValue = dnsProvider,
                    values = dnsProviders,
                    valueText = { it },
                    onValueSelected = onDnsProviderChange,
                )
            }

            item(visible = dnsOverHttpsEnabled && dnsProvider == "Custom") {
                EditTextPreference(
                    title = { Text(stringResource(R.string.dns_custom_url)) },
                    value = customDnsUrl,
                    onValueChange = onCustomDnsUrlChange,
                )
            }

            item(visible = dnsOverHttpsEnabled) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.test_dns_connection)) },
                    icon = { Icon(painterResource(R.drawable.check), null) },
                    onClick = {
                        if (testingDns) return@PreferenceEntry
                        scope.launch {
                            testingDns = true
                            try {
                                val result =
                                    withContext(Dispatchers.IO) {
                                        val providerUrl = YouTube.resolveDnsProviderUrl(dnsProvider, customDnsUrl)
                                        if (providerUrl == null) {
                                            return@withContext context.getString(
                                                R.string.dns_connection_failed,
                                                context.getString(R.string.proxy_connection_invalid_configuration),
                                            )
                                        }
                                        val testDns =
                                            YouTube
                                                .createDnsOverHttps(providerUrl)
                                                .withIpVersionPreference(YouTube.ipVersion)
                                        val testHosts = listOf("music.youtube.com", "googlevideo.com", "jiosaavn.com", "www.jiosaavn.com")
                                        testHosts.forEach { host ->
                                            val addresses = testDns.lookup(host)
                                            check(addresses.isNotEmpty()) { "No addresses for $host" }
                                        }
                                        val client =
                                            YouTube
                                                .newOkHttpClientBuilder()
                                                .dns(testDns)
                                                .connectTimeout(10, TimeUnit.SECONDS)
                                                .readTimeout(10, TimeUnit.SECONDS)
                                                .build()
                                        val probeUrls = listOf(
                                            "https://music.youtube.com/generate_204",
                                            "https://www.jiosaavn.com/api.php?__call=song.getDetails&_format=json&pids=test",
                                        )
                                        var allSuccess = true
                                        for (url in probeUrls) {
                                            val req = Request.Builder().url(url).build()
                                            val resp = client.newCall(req).execute()
                                            if (!resp.isSuccessful && resp.code != 204) {
                                                allSuccess = false
                                            }
                                            resp.close()
                                        }
                                        if (allSuccess) {
                                            context.getString(R.string.dns_connection_success)
                                        } else {
                                            context.getString(R.string.dns_connection_failed, "Some endpoints failed")
                                        }
                                    }
                                dnsTestResult = result
                            } catch (e: Exception) {
                                dnsTestResult =
                                    context.getString(
                                        R.string.dns_connection_failed,
                                        e.message ?: context.getString(R.string.error_unknown),
                                    )
                            } finally {
                                testingDns = false
                            }
                        }
                    },
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.proxy)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.enable_proxy)) },
                    icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                    checked = proxyEnabled,
                    onCheckedChange = {
                        onProxyEnabledChange(it)
                        ProxyUtils.applyYouTubeProxy(it, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword)
                    },
                )
            }

            item(visible = proxyEnabled) {
                ListPreference(
                    title = { Text(stringResource(R.string.proxy_type)) },
                    selectedValue = proxyType,
                    values = proxyTypes,
                    valueText = { it.name },
                    onValueSelected = {
                        onProxyTypeChange(it)
                        ProxyUtils.applyYouTubeProxy(proxyEnabled, it, proxyHost, proxyPort, proxyUsername, proxyPassword)
                    },
                )
            }

            item(visible = proxyEnabled) {
                EditTextPreference(
                    title = { Text(stringResource(R.string.proxy_host)) },
                    value = proxyHost,
                    onValueChange = {
                        onProxyHostChange(it)
                        ProxyUtils.applyYouTubeProxy(proxyEnabled, proxyType, it, proxyPort, proxyUsername, proxyPassword)
                    },
                )
            }

            item(visible = proxyEnabled) {
                NumberEditTextPreference(
                    title = { Text(stringResource(R.string.proxy_port)) },
                    value = proxyPort,
                    onValueChange = {
                        onProxyPortChange(it)
                        ProxyUtils.applyYouTubeProxy(proxyEnabled, proxyType, proxyHost, it, proxyUsername, proxyPassword)
                    },
                    isInputValid = { it.toIntOrNull() in 1..65535 },
                )
            }
        }

        if (proxyEnabled) {
            PreferenceGroup(title = stringResource(R.string.proxy_auth)) {
                item {
                    EditTextPreference(
                        title = { Text(stringResource(R.string.proxy_username)) },
                        value = proxyUsername,
                        onValueChange = {
                            onProxyUsernameChange(it)
                            ProxyUtils.applyYouTubeProxy(proxyEnabled, proxyType, proxyHost, proxyPort, it, proxyPassword)
                        },
                    )
                }

                item {
                    EditTextPreference(
                        title = { Text(stringResource(R.string.proxy_password)) },
                        value = proxyPassword,
                        onValueChange = {
                            onProxyPasswordChange(it)
                            ProxyUtils.applyYouTubeProxy(proxyEnabled, proxyType, proxyHost, proxyPort, proxyUsername, it)
                        },
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_bypass_proxy)) },
                        description = stringResource(R.string.stream_bypass_proxy_desc),
                        icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                        checked = streamBypassProxy,
                        onCheckedChange = {
                            onStreamBypassProxyChange(it)
                            YouTube.streamBypassProxy = it
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.test_proxy_connection)) },
                        icon = { Icon(painterResource(R.drawable.check), null) },
                        onClick = {
                            if (testingProxy) return@PreferenceEntry
                            scope.launch {
                                testingProxy = true
                                try {
                                    val result =
                                        withContext(Dispatchers.IO) {
                                            val proxy = ProxyUtils.createProxyOrNull(proxyType, proxyHost, proxyPort)
                                            if (proxy == null) {
                                                return@withContext context.getString(
                                                    R.string.proxy_connection_failed,
                                                    context.getString(R.string.proxy_connection_invalid_configuration),
                                                )
                                            }
                                            val clientBuilder =
                                                OkHttpClient
                                                    .Builder()
                                                    .proxy(proxy)
                                                    .connectTimeout(10, TimeUnit.SECONDS)
                                                    .readTimeout(10, TimeUnit.SECONDS)

                                            if (proxyUsername.isNotBlank() && proxyPassword.isNotBlank()) {
                                                clientBuilder.proxyAuthenticator { _, response ->
                                                    val credential = okhttp3.Credentials.basic(proxyUsername, proxyPassword)
                                                    response.request
                                                        .newBuilder()
                                                        .header("Proxy-Authorization", credential)
                                                        .build()
                                                }
                                            }

                                            val client = clientBuilder.build()
                                            val request =
                                                Request
                                                    .Builder()
                                                    .url("https://music.youtube.com/generate_204")
                                                    .build()
                                            client.newCall(request).execute().use { response ->
                                                if (response.isSuccessful || response.code == 204) {
                                                    context.getString(R.string.proxy_connection_success)
                                                } else {
                                                    context.getString(R.string.proxy_connection_failed, "HTTP ${response.code}")
                                                }
                                            }
                                        }
                                    testResult = result
                                } catch (e: Exception) {
                                    testResult =
                                        context.getString(
                                            R.string.proxy_connection_failed,
                                            e.message ?: context.getString(R.string.error_unknown),
                                        )
                                } finally {
                                    testingProxy = false
                                }
                            }
                        },
                    )
                }
            }
        }

        PreferenceGroup(title = stringResource(R.string.ip_rotation)) {
            item {
                IpRotationPreference(
                    title = { Text(stringResource(R.string.ip_rotation)) },
                    description = ipRotationDescription,
                    icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                    checked = ipRotationEnabled,
                    isBusy = loadingIpRotation || refreshingIpRotation,
                    onCheckedChange = { enabled ->
                        onIpRotationEnabledChange(enabled)
                        if (enabled) {
                            scope.launch {
                                loadingIpRotation = true
                                try {
                                    YouTube.enableIpRotation()
                                } catch (_: Exception) {
                                    onIpRotationEnabledChange(false)
                                } finally {
                                    loadingIpRotation = false
                                }
                            }
                        } else {
                            YouTube.disableIpRotation()
                        }
                    },
                    onRefresh = refreshIp@{
                        if (loadingIpRotation || refreshingIpRotation) return@refreshIp
                        scope.launch {
                            refreshingIpRotation = true
                            try {
                                YouTube.refreshIpRotation()
                            } catch (_: Exception) {
                            } finally {
                                refreshingIpRotation = false
                            }
                        }
                    },
                )
            }
        }
        }

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter),
            title = { Text(stringResource(R.string.internet)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
        )
    }

    if (testingDns) {
        DefaultDialog(
            onDismiss = { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.testing_dns_connection))
        }
    }

    if (dnsTestResult != null) {
        ActionPromptDialog(
            title = stringResource(R.string.test_dns_connection),
            onDismiss = { dnsTestResult = null },
            onConfirm = { dnsTestResult = null },
            content = {
                Text(dnsTestResult!!)
            },
        )
    }

    if (testingProxy) {
        DefaultDialog(
            onDismiss = { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.testing_proxy_connection))
        }
    }

    if (testResult != null) {
        ActionPromptDialog(
            title = stringResource(R.string.test_proxy_connection),
            onDismiss = { testResult = null },
            onConfirm = { testResult = null },
            content = {
                Text(testResult!!)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IpRotationPreference(
    title: @Composable () -> Unit,
    description: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    isBusy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    PreferenceEntry(
        title = title,
        description = description,
        icon = icon,
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (checked) {
                    if (isBusy) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        FilledTonalIconButton(onClick = onRefresh) {
                            Icon(
                                painterResource(R.drawable.sync),
                                contentDescription = stringResource(R.string.ip_rotation_refresh),
                            )
                        }
                    }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = !isBusy,
                    thumbContent = {
                        AnimatedContent(
                            targetState = checked,
                            transitionSpec = {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            },
                            label = "ipRotationSwitchThumbIcon",
                        ) { isChecked ->
                            Icon(
                                painter =
                                    painterResource(
                                        id = if (isChecked) R.drawable.check else R.drawable.close,
                                    ),
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    },
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedIconColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            uncheckedIconColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }
        },
        onClick =
            if (isBusy) {
                null
            } else {
                { onCheckedChange(!checked) }
            },
    )
}
