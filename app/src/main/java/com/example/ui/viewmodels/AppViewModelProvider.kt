package com.example.ui.viewmodels

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.EarthlinkApp

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            AuthViewModel(
                gateway = app.earthlinkGateway,
                prefs = app.preferenceManager,
                audit = app.auditRepository,
                syncRepo = app.syncRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            DashboardViewModel(
                gateway = app.earthlinkGateway,
                audit = app.auditRepository,
                localAccountRepository = app.localAccountRepository,
                localLedgerRepository = app.localLedgerRepository,
                syncRepo = app.syncRepository,
                prefs = app.preferenceManager
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            EarthlinkSearchViewModel(
                gateway = app.earthlinkGateway,
                audit = app.auditRepository,
                prefs = app.preferenceManager,
                localAccountRepository = app.localAccountRepository,
                localLedgerRepository = app.localLedgerRepository,
                syncRepo = app.syncRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            LocalAccountsViewModel(
                localRepo = app.localAccountRepository,
                ledgerRepo = app.localLedgerRepository,
                utowerRepo = app.utowerImportRepository,
                audit = app.auditRepository,
                syncRepo = app.syncRepository,
                appDatabase = app.database,
                prefs = app.preferenceManager
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            StatementViewModel(
                gateway = app.earthlinkGateway,
                ledgerRepository = app.localLedgerRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            SyncStatusViewModel(
                syncRepo = app.syncRepository,
                audit = app.auditRepository,
                prefs = app.preferenceManager
            )
        }
    }
}
