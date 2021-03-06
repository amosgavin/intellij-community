// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:Suppress("PackageDirectoryMismatch")

package com.intellij.ide.passwordSafe.impl

import com.intellij.credentialStore.*
import com.intellij.credentialStore.kdbx.IncorrectMasterPasswordException
import com.intellij.credentialStore.kdbx.KeePassDatabase
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordStorage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SettingsSavingComponent
import com.intellij.openapi.diagnostic.runAndLogException
import org.jetbrains.annotations.TestOnly
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.runAsync
import java.nio.file.Paths

private fun computeProvider(settings: PasswordSafeSettings): CredentialStore {
  if (settings.providerType == ProviderType.MEMORY_ONLY || (ApplicationManager.getApplication()?.isUnitTestMode == true)) {
    return KeePassCredentialStore(isMemoryOnly = true)
  }

  if (settings.providerType != ProviderType.KEEPASS) {
    return createPersistentCredentialStore()
  }

  val dbFile = LOG.runAndLogException { Paths.get(settings.keepassDb!!) }
  try {
    return KeePassCredentialStore(dbFile = dbFile)
  }
  catch (e: IncorrectMasterPasswordException) {
    LOG.warn(e)
    NOTIFICATION_MANAGER.notify("Master password for KeePass database is not correct ($dbFile). In-memory password storage will be used.", null)
  }
  catch (e: Throwable) {
    LOG.error(e)
    NOTIFICATION_MANAGER.notify("Internal error during opening of KeePass database($dbFile). In-memory password storage will be used.", null)
  }

  settings.providerType = ProviderType.MEMORY_ONLY
  return KeePassCredentialStore(isMemoryOnly = true)
}

class PasswordSafeImpl @JvmOverloads constructor(val settings: PasswordSafeSettings /* public - backward compatibility */,
                                                 provider: CredentialStore? = null) : PasswordSafe(), SettingsSavingComponent {
  override var isRememberPasswordByDefault: Boolean
    get() = settings.state.isRememberPasswordByDefault
    set(value) {
      settings.state.isRememberPasswordByDefault = value
    }

  private var _currentProvider: Lazy<CredentialStore> = if (provider == null) lazy { computeProvider(settings) } else lazyOf(provider)

  internal val currentProviderIfComputed: CredentialStore?
    get() = if (_currentProvider.isInitialized()) _currentProvider.value else null

  internal var currentProvider: CredentialStore
    get() = _currentProvider.value
    set(value) {
      _currentProvider = lazyOf(value)
    }

  // it is helper storage to support set password as memory-only (see setPassword memoryOnly flag)
  private val memoryHelperProvider = lazy { KeePassCredentialStore(preloadedDb = KeePassDatabase(), isMemoryOnly = true) }

  override val isMemoryOnly: Boolean
    get() = settings.providerType == ProviderType.MEMORY_ONLY

  override fun get(attributes: CredentialAttributes): Credentials? {
    val value = currentProvider.get(attributes)
    if ((value == null || value.password.isNullOrEmpty()) && memoryHelperProvider.isInitialized()) {
      // if password was set as `memoryOnly`
      memoryHelperProvider.value.get(attributes)?.let {
        if (!it.isEmpty()) {
          return it
        }
      }
    }
    return value
  }

  override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
    currentProvider.set(attributes, credentials)
    if (attributes.isPasswordMemoryOnly && !credentials?.password.isNullOrEmpty()) {
      // we must store because otherwise on get will be no password
      memoryHelperProvider.value.set(attributes.toPasswordStoreable(), credentials)
    }
    else if (memoryHelperProvider.isInitialized()) {
      memoryHelperProvider.value.set(attributes, null)
    }
  }

  override fun set(attributes: CredentialAttributes, credentials: Credentials?, memoryOnly: Boolean) {
    if (memoryOnly) {
      memoryHelperProvider.value.set(attributes.toPasswordStoreable(), credentials)
      // remove to ensure that on getPassword we will not return some value from default provider
      currentProvider.set(attributes, null)
    }
    else {
      set(attributes, credentials)
    }
  }

  // maybe in the future we will use native async, so, this method added here instead "if need, just use runAsync in your code"
  override fun getAsync(attributes: CredentialAttributes): Promise<Credentials?> = runAsync { get(attributes) }

  override fun save() {
    (currentProviderIfComputed as? KeePassCredentialStore)?.save()
  }

  override fun isPasswordStoredOnlyInMemory(attributes: CredentialAttributes, credentials: Credentials): Boolean {
    if (isMemoryOnly || credentials.password.isNullOrEmpty()) {
      return true
    }

    if (!memoryHelperProvider.isInitialized()) {
      return false
    }

    return memoryHelperProvider.value.get(attributes)?.let {
      !it.password.isNullOrEmpty()
    } ?: false
  }

  // public - backward compatibility
  @Suppress("unused", "DeprecatedCallableAddReplaceWith")
  @Deprecated("Do not use it")
  val masterKeyProvider: CredentialStore
    get() = currentProvider

  @Suppress("unused")
  @Deprecated("Do not use it")
  // public - backward compatibility
  val memoryProvider: PasswordStorage
    get() = memoryHelperProvider.value
}

internal fun createPersistentCredentialStore(existing: KeePassCredentialStore? = null, convertFileStore: Boolean = false): PasswordStorage {
  LOG.runAndLogException {
    for (factory in CredentialStoreFactory.CREDENTIAL_STORE_FACTORY.extensions) {
      val store = factory.create() ?: continue
      if (convertFileStore) {
        LOG.runAndLogException {
          val fileStore = KeePassCredentialStore()
          fileStore.copyTo(store)
          fileStore.clear()
          fileStore.save()
        }
      }
      return store
    }
  }

  existing?.let {
    it.isMemoryOnly = false
    return it
  }
  return KeePassCredentialStore()
}

@TestOnly
internal fun createKeePassStore(file: String): PasswordSafe {
  val settings = PasswordSafeSettings()
  settings.loadState(PasswordSafeSettings.PasswordSafeOptions().apply {
    provider = ProviderType.KEEPASS
    keepassDb = file
  })
  return PasswordSafeImpl(settings, KeePassCredentialStore(dbFile = Paths.get(file)))
}