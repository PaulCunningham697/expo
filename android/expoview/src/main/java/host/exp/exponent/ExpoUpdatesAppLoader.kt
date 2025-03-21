// Copyright 2015-present 650 Industries. All rights reserved.
package host.exp.exponent

import android.content.Context
import android.net.Uri
import android.util.Log
import expo.modules.jsonutils.getNullable
import expo.modules.manifests.core.LegacyManifest
import expo.modules.core.utilities.EmulatorUtilities
import expo.modules.updates.UpdatesConfiguration
import expo.modules.updates.UpdatesUtils
import expo.modules.updates.db.DatabaseHolder
import expo.modules.updates.db.entity.UpdateEntity
import expo.modules.updates.launcher.Launcher
import expo.modules.updates.launcher.NoDatabaseLauncher
import expo.modules.updates.loader.FileDownloader
import expo.modules.updates.loader.LoaderTask
import expo.modules.updates.loader.LoaderTask.BackgroundUpdateStatus
import expo.modules.updates.loader.LoaderTask.LoaderTaskCallback
import expo.modules.updates.manifest.UpdateManifest
import expo.modules.manifests.core.Manifest
import expo.modules.updates.codesigning.CODE_SIGNING_METADATA_ALGORITHM_KEY
import expo.modules.updates.codesigning.CODE_SIGNING_METADATA_KEY_ID_KEY
import expo.modules.updates.codesigning.CodeSigningAlgorithm
import expo.modules.updates.manifest.EmbeddedManifest
import expo.modules.updates.selectionpolicy.LauncherSelectionPolicyFilterAware
import expo.modules.updates.selectionpolicy.LoaderSelectionPolicyFilterAware
import expo.modules.updates.selectionpolicy.ReaperSelectionPolicyDevelopmentClient
import expo.modules.updates.selectionpolicy.SelectionPolicy
import host.exp.exponent.di.NativeModuleDepsProvider
import host.exp.exponent.exceptions.ManifestException
import host.exp.exponent.kernel.ExperienceKey
import host.exp.exponent.kernel.ExpoViewKernel
import host.exp.exponent.kernel.Kernel
import host.exp.exponent.kernel.KernelConfig
import host.exp.exponent.storage.ExponentSharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.*
import javax.inject.Inject

private const val UPDATE_AVAILABLE_EVENT = "updateAvailable"
private const val UPDATE_NO_UPDATE_AVAILABLE_EVENT = "noUpdateAvailable"
private const val UPDATE_ERROR_EVENT = "error"

/**
 * Entry point to expo-updates in Expo Go and legacy standalone builds. Fulfills many of the
 * purposes of [UpdatesController] along with serving as an interface to the rest of the ExpoKit
 * kernel.
 *
 * Dynamically generates a configuration object with the correct scope key, and then, like
 * [UpdatesController], delegates to an instance of [LoaderTask] to start the process of loading and
 * launching an update, and responds appropriately depending on the callbacks that are invoked.
 *
 * Multiple instances of ExpoUpdatesAppLoader can exist at a time; instances are retained by
 * [Kernel].
 */
class ExpoUpdatesAppLoader @JvmOverloads constructor(
  private val manifestUrl: String,
  private val callback: AppLoaderCallback,
  private val useCacheOnly: Boolean = false
) {
  @Inject
  lateinit var exponentManifest: ExponentManifest

  @Inject
  lateinit var exponentSharedPreferences: ExponentSharedPreferences

  @Inject
  lateinit var databaseHolder: DatabaseHolder

  @Inject
  lateinit var kernel: Kernel

  enum class AppLoaderStatus {
    CHECKING_FOR_UPDATE, DOWNLOADING_NEW_UPDATE
  }

  var isEmergencyLaunch = false
    private set
  var isUpToDate = true
    private set
  var status: AppLoaderStatus? = null
    private set
  var shouldShowAppLoaderStatus = true
    private set
  private var isStarted = false

  interface AppLoaderCallback {
    fun onOptimisticManifest(optimisticManifest: Manifest)
    fun onManifestCompleted(manifest: Manifest)
    fun onBundleCompleted(localBundlePath: String)
    fun emitEvent(params: JSONObject)
    fun updateStatus(status: AppLoaderStatus)
    fun onError(e: Exception)
  }

  lateinit var updatesConfiguration: UpdatesConfiguration
    private set

  lateinit var updatesDirectory: File
    private set

  lateinit var selectionPolicy: SelectionPolicy
    private set

  lateinit var fileDownloader: FileDownloader
    private set

  lateinit var launcher: Launcher
    private set

  private fun updateStatus(status: AppLoaderStatus) {
    this.status = status
    callback.updateStatus(status)
  }

  fun start(context: Context) {
    check(!isStarted) { "AppLoader for $manifestUrl was started twice. AppLoader.start() may only be called once per instance." }
    isStarted = true
    status = AppLoaderStatus.CHECKING_FOR_UPDATE
    fileDownloader = FileDownloader(context)
    kernel.addAppLoaderForManifestUrl(manifestUrl, this)
    val httpManifestUrl = exponentManifest.httpManifestUrl(manifestUrl)
    var releaseChannel = Constants.RELEASE_CHANNEL
    if (!Constants.isStandaloneApp()) {
      // in Expo Go, the release channel can change at runtime depending on the URL we load
      val releaseChannelQueryParam =
        httpManifestUrl.getQueryParameter(ExponentManifest.QUERY_PARAM_KEY_RELEASE_CHANNEL)
      if (releaseChannelQueryParam != null) {
        releaseChannel = releaseChannelQueryParam
      }
    }
    val configMap = mutableMapOf<String, Any>()
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_UPDATE_URL_KEY] = httpManifestUrl
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_SCOPE_KEY_KEY] = httpManifestUrl.toString()
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_SDK_VERSION_KEY] = Constants.SDK_VERSIONS
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_RELEASE_CHANNEL_KEY] = releaseChannel
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_HAS_EMBEDDED_UPDATE_KEY] = Constants.isStandaloneApp()
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_ENABLED_KEY] = Constants.ARE_REMOTE_UPDATES_ENABLED
    if (useCacheOnly) {
      configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CHECK_ON_LAUNCH_KEY] = "NEVER"
      configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_LAUNCH_WAIT_MS_KEY] = 0
    } else {
      if (Constants.isStandaloneApp()) {
        configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CHECK_ON_LAUNCH_KEY] = if (Constants.UPDATES_CHECK_AUTOMATICALLY) "ALWAYS" else "NEVER"
        configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_LAUNCH_WAIT_MS_KEY] = Constants.UPDATES_FALLBACK_TO_CACHE_TIMEOUT
      } else {
        configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CHECK_ON_LAUNCH_KEY] = "ALWAYS"
        configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_LAUNCH_WAIT_MS_KEY] = 60000
      }
    }
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_REQUEST_HEADERS_KEY] = requestHeaders
    configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_EXPECTS_EXPO_SIGNED_MANIFEST] = true
    if (!Constants.isStandaloneApp()) {
      // in Expo Go, embed the Expo Root Certificate and get the Expo Go intermediate certificate and development certificates from the multipart manifest response part
      configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CODE_SIGNING_CERTIFICATE] = context.assets.open("expo-root.pem").readBytes().decodeToString()
      configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CODE_SIGNING_METADATA] = mapOf(
        CODE_SIGNING_METADATA_KEY_ID_KEY to "expo-root",
        CODE_SIGNING_METADATA_ALGORITHM_KEY to CodeSigningAlgorithm.RSA_SHA256.algorithmName,
      )
      configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CODE_SIGNING_INCLUDE_MANIFEST_RESPONSE_CERTIFICATE_CHAIN] = true
      configMap[UpdatesConfiguration.UPDATES_CONFIGURATION_CODE_SIGNING_ALLOW_UNSIGNED_MANIFESTS] = true
    }

    val configuration = UpdatesConfiguration(null, configMap)
    val sdkVersionsList = mutableListOf<String>().apply {
      (Constants.SDK_VERSIONS_LIST + listOf(RNObject.UNVERSIONED)).forEach {
        add(it)
        add("exposdk:$it")
      }
    }
    val selectionPolicy = SelectionPolicy(
      LauncherSelectionPolicyFilterAware(sdkVersionsList),
      LoaderSelectionPolicyFilterAware(),
      ReaperSelectionPolicyDevelopmentClient()
    )
    val directory: File = try {
      UpdatesUtils.getOrCreateUpdatesDirectory(context)
    } catch (e: Exception) {
      callback.onError(e)
      return
    }
    startLoaderTask(configuration, directory, selectionPolicy, context)
  }

  private fun startLoaderTask(
    configuration: UpdatesConfiguration,
    directory: File,
    selectionPolicy: SelectionPolicy,
    context: Context
  ) {
    updatesConfiguration = configuration
    updatesDirectory = directory
    this.selectionPolicy = selectionPolicy
    if (!configuration.isEnabled) {
      launchWithNoDatabase(context, null)
      return
    }
    LoaderTask(
      configuration,
      databaseHolder,
      directory,
      fileDownloader,
      selectionPolicy,
      object : LoaderTaskCallback {
        private var didAbort = false
        override fun onFailure(e: Exception) {
          if (Constants.isStandaloneApp()) {
            isEmergencyLaunch = true
            launchWithNoDatabase(context, e)
          } else {
            if (didAbort) {
              return
            }
            var exception = e
            try {
              val errorJson = JSONObject(e.message!!)
              exception = ManifestException(e, manifestUrl, errorJson)
            } catch (ex: Exception) {
              // do nothing, expected if the error payload does not come from a conformant server
            }
            callback.onError(exception)
          }
        }

        override fun onCachedUpdateLoaded(update: UpdateEntity): Boolean {
          val manifest = Manifest.fromManifestJson(update.manifest!!)
          setShouldShowAppLoaderStatus(manifest)
          if (manifest.isUsingDeveloperTool()) {
            return false
          } else {
            try {
              val experienceKey = ExperienceKey.fromManifest(manifest)
              // if previous run of this app failed due to a loading error, we want to make sure to check for remote updates
              val experienceMetadata = exponentSharedPreferences.getExperienceMetadata(experienceKey)
              if (experienceMetadata != null && experienceMetadata.optBoolean(
                  ExponentSharedPreferences.EXPERIENCE_METADATA_LOADING_ERROR
                )
              ) {
                return false
              }
            } catch (e: Exception) {
              return true
            }
          }
          return true
        }

        override fun onRemoteUpdateManifestLoaded(updateManifest: UpdateManifest) {
          // expo-cli does not always respect our SDK version headers and respond with a compatible update or an error
          // so we need to check the compatibility here
          val sdkVersion = updateManifest.manifest.getSDKVersion()
          if (!isValidSdkVersion(sdkVersion)) {
            callback.onError(formatExceptionForIncompatibleSdk(sdkVersion))
            didAbort = true
            return
          }
          setShouldShowAppLoaderStatus(updateManifest.manifest)
          callback.onOptimisticManifest(updateManifest.manifest)
          updateStatus(AppLoaderStatus.DOWNLOADING_NEW_UPDATE)
        }

        override fun onSuccess(launcher: Launcher, isUpToDate: Boolean) {
          if (didAbort) {
            return
          }
          this@ExpoUpdatesAppLoader.launcher = launcher
          this@ExpoUpdatesAppLoader.isUpToDate = isUpToDate
          try {
            val manifestJson = processManifestJson(launcher.launchedUpdate!!.manifest!!)
            val manifest = Manifest.fromManifestJson(manifestJson)
            callback.onManifestCompleted(manifest)

            // ReactAndroid will load the bundle on its own in development mode
            if (!manifest.isDevelopmentMode()) {
              callback.onBundleCompleted(launcher.launchAssetFile!!)
            }
          } catch (e: Exception) {
            callback.onError(e)
          }
        }

        override fun onBackgroundUpdateFinished(
          status: BackgroundUpdateStatus,
          update: UpdateEntity?,
          exception: Exception?
        ) {
          if (didAbort) {
            return
          }
          try {
            val jsonParams = JSONObject()
            when (status) {
              BackgroundUpdateStatus.ERROR -> {
                if (exception == null) {
                  throw AssertionError("Background update with error status must have a nonnull exception object")
                }
                jsonParams.put("type", UPDATE_ERROR_EVENT)
                jsonParams.put("message", exception.message)
              }
              BackgroundUpdateStatus.UPDATE_AVAILABLE -> {
                if (update == null) {
                  throw AssertionError("Background update with error status must have a nonnull update object")
                }
                jsonParams.put("type", UPDATE_AVAILABLE_EVENT)
                jsonParams.put("manifestString", update.manifest.toString())
              }
              BackgroundUpdateStatus.NO_UPDATE_AVAILABLE -> {
                jsonParams.put("type", UPDATE_NO_UPDATE_AVAILABLE_EVENT)
              }
            }
            callback.emitEvent(jsonParams)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to emit event to JS", e)
          }
        }
      }
    ).start(context)
  }

  private fun launchWithNoDatabase(context: Context, e: Exception?) {
    this.launcher = NoDatabaseLauncher(context, updatesConfiguration, e)
    var manifestJson = EmbeddedManifest.get(context, updatesConfiguration)!!.manifest.getRawJson()
    try {
      manifestJson = processManifestJson(manifestJson)
    } catch (ex: Exception) {
      Log.e(
        TAG,
        "Failed to process manifest; attempting to launch with raw manifest. This may cause errors or unexpected behavior.",
        e
      )
    }
    callback.onManifestCompleted(Manifest.fromManifestJson(manifestJson))
    // ReactInstanceManagerBuilder accepts embedded assets as strings with "assets://" prefixed
    val launchAssetFile = launcher.launchAssetFile ?: "assets://" + launcher.bundleAssetName
    callback.onBundleCompleted(launchAssetFile)
  }

  @Throws(JSONException::class)
  private fun processManifestJson(manifestJson: JSONObject): JSONObject {
    val parsedManifestUrl = Uri.parse(manifestUrl)

    // If legacy manifest is not yet verified, served by a third party, not standalone, and not an anonymous experience
    // then scope it locally by using the manifest URL as a scopeKey (id) and consider it verified.
    if (!manifestJson.optBoolean(ExponentManifest.MANIFEST_IS_VERIFIED_KEY, false) &&
      isThirdPartyHosted(parsedManifestUrl) &&
      !Constants.isStandaloneApp() &&
      !exponentManifest.isAnonymousExperience(Manifest.fromManifestJson(manifestJson)) &&
      Manifest.fromManifestJson(manifestJson) is LegacyManifest
    ) {
      // for https urls, sandboxed id is of form quinlanj.github.io/myProj-myApp
      // for http urls, sandboxed id is of form UNVERIFIED-quinlanj.github.io/myProj-myApp
      val protocol = parsedManifestUrl.scheme
      val securityPrefix = if (protocol == "https" || protocol == "exps") "" else "UNVERIFIED-"
      val path = if (parsedManifestUrl.path != null) parsedManifestUrl.path else ""
      val slug = manifestJson.getNullable<String>(ExponentManifest.MANIFEST_SLUG) ?: ""
      val sandboxedId = securityPrefix + parsedManifestUrl.host + path + "-" + slug
      manifestJson.put(ExponentManifest.MANIFEST_ID_KEY, sandboxedId)
      manifestJson.put(ExponentManifest.MANIFEST_IS_VERIFIED_KEY, true)
    }

    // all standalone apps are considered verified
    if (Constants.isStandaloneApp()) {
      manifestJson.put(ExponentManifest.MANIFEST_IS_VERIFIED_KEY, true)
    }

    // if the manifest is scoped to a random anonymous scope key, automatically verify it
    if (exponentManifest.isAnonymousExperience(Manifest.fromManifestJson(manifestJson))) {
      manifestJson.put(ExponentManifest.MANIFEST_IS_VERIFIED_KEY, true)
    }

    // otherwise set verified to false
    if (!manifestJson.has(ExponentManifest.MANIFEST_IS_VERIFIED_KEY)) {
      manifestJson.put(ExponentManifest.MANIFEST_IS_VERIFIED_KEY, false)
    }

    return manifestJson
  }

  private fun isThirdPartyHosted(uri: Uri): Boolean {
    val host = uri.host
    return !(
      host == "exp.host" || host == "expo.io" || host == "exp.direct" || host == "expo.test" ||
        host!!.endsWith(".exp.host") || host.endsWith(".expo.io") || host.endsWith(".exp.direct") || host.endsWith(
        ".expo.test"
      )
      )
  }

  private fun setShouldShowAppLoaderStatus(manifest: Manifest) {
    // we don't want to show the cached experience alert when Updates.reloadAsync() is called
    if (useCacheOnly) {
      shouldShowAppLoaderStatus = false
      return
    }
    shouldShowAppLoaderStatus = !manifest.isDevelopmentSilentLaunch()
  }

  // XDL expects the full "exponent-" header names
  private val requestHeaders: Map<String, String?>
    get() {
      val headers = mutableMapOf<String, String>()
      headers["Expo-Updates-Environment"] = clientEnvironment
      headers["Expo-Client-Environment"] = clientEnvironment
      val versionName = ExpoViewKernel.instance.versionName
      if (versionName != null) {
        headers["Exponent-Version"] = versionName
      }
      val sessionSecret = exponentSharedPreferences.sessionSecret
      if (sessionSecret != null) {
        headers["Expo-Session"] = sessionSecret
      }

      // XDL expects the full "exponent-" header names
      headers["Exponent-Accept-Signature"] = "true"
      headers["Exponent-Platform"] = "android"
      if (KernelConfig.FORCE_UNVERSIONED_PUBLISHED_EXPERIENCES) {
        headers["Exponent-SDK-Version"] = "UNVERSIONED"
      } else {
        headers["Exponent-SDK-Version"] = Constants.SDK_VERSIONS
      }
      return headers
    }

  private val isRunningOnEmulator: Boolean
    get() = EmulatorUtilities.isRunningOnEmulator()

  private val clientEnvironment: String
    get() = if (Constants.isStandaloneApp()) {
      "STANDALONE"
    } else if (EmulatorUtilities.isRunningOnEmulator()) {
      "EXPO_SIMULATOR"
    } else {
      "EXPO_DEVICE"
    }

  private fun isValidSdkVersion(sdkVersion: String?): Boolean {
    if (sdkVersion == null) {
      return false
    }
    if (RNObject.UNVERSIONED == sdkVersion) {
      return true
    }
    for (version in Constants.SDK_VERSIONS_LIST) {
      if (version == sdkVersion) {
        return true
      }
    }
    return false
  }

  private fun formatExceptionForIncompatibleSdk(sdkVersion: String?): ManifestException {
    val errorJson = JSONObject()
    try {
      errorJson.put("message", "Invalid SDK version")
      if (sdkVersion == null) {
        errorJson.put("errorCode", "NO_SDK_VERSION_SPECIFIED")
      } else if (ABIVersion.toNumber(sdkVersion) > ABIVersion.toNumber(Constants.SDK_VERSIONS_LIST[0])) {
        errorJson.put("errorCode", "EXPERIENCE_SDK_VERSION_TOO_NEW")
      } else {
        errorJson.put("errorCode", "EXPERIENCE_SDK_VERSION_OUTDATED")
        errorJson.put(
          "metadata",
          JSONObject().put(
            "availableSDKVersions",
            JSONArray().put(sdkVersion)
          )
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to format error message for incompatible SDK version", e)
    }
    return ManifestException(Exception("Incompatible SDK version"), manifestUrl, errorJson)
  }

  companion object {
    private val TAG = ExpoUpdatesAppLoader::class.java.simpleName
    const val UPDATES_EVENT_NAME = "Expo.nativeUpdatesEvent"
  }

  init {
    NativeModuleDepsProvider.instance.inject(ExpoUpdatesAppLoader::class.java, this)
  }
}
