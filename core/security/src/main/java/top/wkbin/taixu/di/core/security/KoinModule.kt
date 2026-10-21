package top.wkbin.taixu.di.core.security

import org.koin.dsl.module
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.core.security.SecretRedactor

/** Dependency registrations owned by the core:security module. */
val coreSecurityModule = module {
    single<SecretManager> { SecretManager() }

    single<SecretRedactor> { SecretRedactor() }

    single<SensitiveDataRedactor> { get<SecretRedactor>() }
}
