package com.strataspent.app

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.strataspent.app.data.AiAnalyticsRepository
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.GroupRepository
import com.strataspent.app.data.LanguagePreference
import com.strataspent.app.data.OcrProcessingWorker
import com.strataspent.app.data.OcrRepository
import com.strataspent.app.data.PendingOcrRepository
import com.strataspent.app.data.RemindersRepository
import com.strataspent.app.data.UserDirectoryRepository

/**
 * Hand-rolled service locator. Each repository is a singleton bound to the
 * Firebase singletons. Swap to Hilt later by deleting this class and the
 * `StrataViewModelFactory` together.
 */
class ServiceLocator(application: Application) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    // Target the named Firestore database inside the project (the (default)
    // database does not exist). Firestore added multi-database-per-project
    // support in SDK 25 (Firebase BoM 32.7+).
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(FIRESTORE_DB_ID)

    val authRepo: AuthRepository = AuthRepository(auth, firestore)
    val groupRepo: GroupRepository = GroupRepository(firestore)
    val expenseRepo: ExpenseRepository = ExpenseRepository(firestore, auth)
    val userDirectory: UserDirectoryRepository = UserDirectoryRepository(firestore)
    val ocrRepo: OcrRepository = OcrRepository(BuildConfig.GEMINI_API_KEY)
    val aiAnalyticsRepo: AiAnalyticsRepository = AiAnalyticsRepository(BuildConfig.GEMINI_API_KEY)
    val remindersRepo: RemindersRepository = RemindersRepository(firestore)
    val languagePref: LanguagePreference = LanguagePreference(application)
    val pendingOcr: PendingOcrRepository = PendingOcrRepository(application)

    private companion object {
        const val FIRESTORE_DB_ID = "ai-studio-6193fa2f-201b-4627-bfb4-124dbca4a1c2"
    }
}

class StrataSpentApplication : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator(this)
        // Re-schedule the OCR worker on every app start so any items left
        // in the queue from a previous session get picked up as soon as
        // network is available.
        if (locator.pendingOcr.snapshot().isNotEmpty()) {
            OcrProcessingWorker.schedule(this)
        }
    }
}
