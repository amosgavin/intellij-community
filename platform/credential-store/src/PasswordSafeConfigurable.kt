// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore

import com.intellij.credentialStore.kdbx.IncorrectMasterPasswordException
import com.intellij.credentialStore.kdbx.KdbxPassword
import com.intellij.credentialStore.kdbx.loadKdbx
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.impl.PasswordSafeImpl
import com.intellij.ide.passwordSafe.impl.createPersistentCredentialStore
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurableBase
import com.intellij.openapi.options.ConfigurableUi
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.TextFieldWithHistoryWithBrowseButton
import com.intellij.ui.components.RadioButton
import com.intellij.ui.layout.*
import com.intellij.util.io.delete
import com.intellij.util.io.exists
import com.intellij.util.text.nullize
import gnu.trove.THashMap
import java.awt.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.swing.JPanel
import kotlin.properties.Delegates.notNull

internal class PasswordSafeConfigurable(private val settings: PasswordSafeSettings) : ConfigurableBase<PasswordSafeConfigurableUi, PasswordSafeSettings>("application.passwordSafe", "Passwords", "reference.ide.settings.password.safe") {
  override fun getSettings() = settings

  override fun createUi() = PasswordSafeConfigurableUi()
}

internal fun getDefaultKeePassDbFilePath() = "${PathManager.getConfigPath()}${File.separatorChar}${DB_FILE_NAME}"

internal class PasswordSafeConfigurableUi : ConfigurableUi<PasswordSafeSettings> {
  private val inKeychain = RadioButton("In native Keychain")

  private val inKeePass = RadioButton("In KeePass")
  private var keePassDbFile: TextFieldWithHistoryWithBrowseButton by notNull()

  private val rememberPasswordsUntilClosing = RadioButton("Do not save, forget passwords after restart")

  private val modeToRow = THashMap<ProviderType, Row>()
  private var pendingMasterPassword: ByteArray? = null

  override fun reset(settings: PasswordSafeSettings) {
    when (settings.providerType) {
      ProviderType.MEMORY_ONLY -> rememberPasswordsUntilClosing.isSelected = true
      ProviderType.KEYCHAIN -> inKeychain.isSelected = true
      ProviderType.KEEPASS -> inKeePass.isSelected = true
      else -> throw IllegalStateException("Unknown provider type: ${settings.providerType}")
    }

    @Suppress("IfThenToElvis")
    keePassDbFile.text = settings.keepassDb ?: getDefaultKeePassDbFilePath()
    updateEnabledState()
  }

  override fun isModified(settings: PasswordSafeSettings): Boolean {
    return getNewProviderType() != settings.providerType || isKeepassFileLocationChanged(settings)
  }

  private fun isKeepassFileLocationChanged(settings: PasswordSafeSettings): Boolean {
    return getNewProviderType() == ProviderType.KEEPASS && getNewDbFileAsString() != settings.keepassDb
  }

  override fun apply(settings: PasswordSafeSettings) {
    val providerType = getNewProviderType()
    val passwordSafe = PasswordSafe.instance as PasswordSafeImpl
    if (settings.providerType != providerType) {
      @Suppress("NON_EXHAUSTIVE_WHEN")
      when (providerType) {
        ProviderType.MEMORY_ONLY -> {
          if (!changeExistingKeepassStoreIfPossible(settings, passwordSafe, isMemoryOnly = true)) {
            passwordSafe.currentProvider = KeePassCredentialStore(isMemoryOnly = true)
          }
        }

        ProviderType.KEYCHAIN -> {
          passwordSafe.currentProvider = createPersistentCredentialStore(passwordSafe.currentProvider as? KeePassCredentialStore)
        }

        ProviderType.KEEPASS -> {
          runAndHandleIncorrectMasterPasswordException {
            if (!changeExistingKeepassStoreIfPossible(settings, passwordSafe, isMemoryOnly = false)) {
              passwordSafe.currentProvider = KeePassCredentialStore(dbFile = getNewDbFile(), existingMasterPassword = pendingMasterPassword)
            }
          }
          pendingMasterPassword = null
        }

        else -> throw java.lang.IllegalStateException("Unknown provider type: $providerType")
      }
    }
    else if (isKeepassFileLocationChanged(settings)) {
      val newDbFile = getNewDbFile()
      if (newDbFile != null) {
        val currentProviderIfComputed = passwordSafe.currentProviderIfComputed as? KeePassCredentialStore
        if (currentProviderIfComputed == null) {
          runAndHandleIncorrectMasterPasswordException {
            passwordSafe.currentProvider = KeePassCredentialStore(dbFile = getNewDbFile(), existingMasterPassword = pendingMasterPassword)
          }
        }
        else {
          currentProviderIfComputed.dbFile = newDbFile
        }
        settings.keepassDb = newDbFile.toString()
      }
    }

    settings.providerType = providerType
  }

  private fun changeExistingKeepassStoreIfPossible(settings: PasswordSafeSettings, passwordSafe: PasswordSafeImpl, isMemoryOnly: Boolean): Boolean {
    if (settings.providerType != ProviderType.MEMORY_ONLY || settings.providerType != ProviderType.KEEPASS) {
      return false
    }

    // must be used only currentProviderIfComputed - no need to compute because it is unsafe operation (incorrect operation and so)
    // if provider not yet computed, we will create a new one in a safe manner (PasswordSafe manager cannot handle correctly - no access to pending master password, cannot throw exceptions)
    val provider = passwordSafe.currentProviderIfComputed as? KeePassCredentialStore ?: return false
    provider.isMemoryOnly = isMemoryOnly
    if (isMemoryOnly) {
      provider.deleteFileStorage()
    }
    else {
      getNewDbFile()?.let {
        provider.dbFile = it
      }
      if (pendingMasterPassword != null) {
        provider.setMasterPassword(pendingMasterPassword!!)
      }
    }
    return true
  }

  private fun getNewDbFile() = getNewDbFileAsString()?.let { Paths.get(it) }

  private fun getNewDbFileAsString() = keePassDbFile.text.trim().nullize()

  private fun updateEnabledState() {
    modeToRow[ProviderType.KEEPASS]?.subRowsEnabled = getNewProviderType() == ProviderType.KEEPASS
  }

  override fun getComponent(): JPanel {
    val passwordSafe = PasswordSafe.instance as PasswordSafeImpl
    return panel {
      row { label("Save passwords:") }

      buttonGroup({ updateEnabledState() }) {
        if (SystemInfo.isLinux || isMacOsCredentialStoreSupported) {
          row {
            inKeychain()
          }
        }

        modeToRow[ProviderType.KEEPASS] = row {
          inKeePass()
          row("Database:") {
            val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleLocalFileDescriptor().withFileFilter {
              it.name.endsWith(".kdbx")
            }
            keePassDbFile = textFieldWithBrowseButton("KeePass Database File",
                                                      fileChooserDescriptor = fileChooserDescriptor,
                                                      fileChosen = ::normalizeSelectedFile,
                                                      comment = if (SystemInfo.isWindows) null else "Stored using weak encryption. It is recommended to store on encrypted volume for additional security.")
            gearButton(
              object : DumbAwareAction("Clear") {
                override fun actionPerformed(event: AnActionEvent) {
                  if (!MessageDialogBuilder.yesNo("Clear Passwords", "Are you sure want to remove all passwords?").yesText("Remove Passwords").isYes) {
                    return
                  }

                  LOG.info("Passwords cleared", Error())
                  val dbFile = getNewDbFile() ?: return
                  try {
                    val db = KeePassCredentialStore(dbFile = dbFile, existingMasterPassword = pendingMasterPassword)
                    db.clear()
                    db.save()
                  }
                  catch (e: Exception) {
                    // ok, just remove file
                    LOG.error(e)
                    dbFile.delete()
                  }
                }

                override fun update(e: AnActionEvent) {
                  e.presentation.isEnabled = getNewDbFile()?.exists() ?: false
                }
              },
              object : DumbAwareAction("Import") {
                override fun actionPerformed(event: AnActionEvent) {
                  chooseFile(fileChooserDescriptor, event) {
                    importKeepassFile(Paths.get(normalizeSelectedFile(it)), event, passwordSafe)
                  }
                }
              },
              object : DumbAwareAction("Set Master Password") {
                override fun actionPerformed(event: AnActionEvent) {
                  val contextComponent = event.getData(PlatformDataKeys.CONTEXT_COMPONENT) as Component
                  val masterPassword = requestMasterPassword(contextComponent, "Set New Master Password") ?: return
                  val currentProvider = if (passwordSafe.settings.providerType == ProviderType.KEEPASS) passwordSafe.currentProvider else null
                  if (currentProvider is KeePassCredentialStore && !currentProvider.isMemoryOnly) {
                    currentProvider.setMasterPassword(masterPassword)
                    pendingMasterPassword = null
                  }
                  else {
                    pendingMasterPassword = masterPassword
                  }
                }
              }
            )
          }
        }

        row {
          val comment = when {
            passwordSafe.settings.providerType == ProviderType.KEEPASS -> "Existing KeePass file will be removed."
            else -> null
          }
          rememberPasswordsUntilClosing(comment = comment)
        }
      }
    }
  }

  private fun importKeepassFile(dbFile: Path, event: AnActionEvent, passwordSafe: PasswordSafeImpl) {
    val currentDbFile = getNewDbFile()
    if (currentDbFile == dbFile) {
      return
    }

    val contextComponent = event.getData(PlatformDataKeys.CONTEXT_COMPONENT) as Component
    val masterPassword = requestMasterPassword(contextComponent, "Specify Master Password") ?: return
    val database = try {
      loadKdbx(dbFile, KdbxPassword(masterPassword))
    }
    catch (e: Exception) {
      val message: String
      when (e) {
        is IncorrectMasterPasswordException -> {
          message = "Master password is not correct"
          LOG.debug(e)
        }
        else -> {
          message = "Internal error"
          LOG.error(e)
        }
      }
      Messages.showMessageDialog(contextComponent, message, "Cannot Import", Messages.getErrorIcon())
      return
    }

    Files.copy(dbFile, currentDbFile, StandardCopyOption.REPLACE_EXISTING)
    passwordSafe.currentProvider = KeePassCredentialStore(preloadedDb = database, existingMasterPassword = masterPassword, dbFile = currentDbFile)
  }

  private fun getNewProviderType(): ProviderType {
    return when {
      rememberPasswordsUntilClosing.isSelected -> ProviderType.MEMORY_ONLY
      inKeePass.isSelected -> ProviderType.KEEPASS
      else -> ProviderType.KEYCHAIN
    }
  }
}

private fun requestMasterPassword(contextComponent: Component, title: String): ByteArray? {
  return MessagesService.getInstance().showPasswordDialog(contextComponent, "Master Password:", title, null, null)?.trim().nullize()?.toByteArray()
}

private fun normalizeSelectedFile(file: VirtualFile): String {
  return when {
    file.isDirectory -> file.path + File.separator + DB_FILE_NAME
    else -> file.path
  }
}

enum class ProviderType {
  MEMORY_ONLY, KEYCHAIN, KEEPASS,

  // unused, but we cannot remove it because enum value maybe stored in the config and we must correctly deserialize it
  @Deprecated("")
  DO_NOT_STORE
}

private inline fun runAndHandleIncorrectMasterPasswordException(handler: () -> Unit) {
  try {
    handler()
  }
  catch (e: IncorrectMasterPasswordException) {
    throw ConfigurationException("Master password for KeePass database is not correct (\"Clear\" can be used to reset database).")
  }
}