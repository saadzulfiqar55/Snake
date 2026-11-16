package com.saad.snake

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

// ---------------------------------------------------------------------------
// ScoreRepository
//
// Logged-in users  → Firestore:  users/{uid}/highScore (Int)
// Guests           → SharedPreferences via SettingsStore
// ---------------------------------------------------------------------------
class ScoreRepository(private val context: Context) {

    private val auth get() = FirebaseAuth.getInstance()
    private val db   get() = FirebaseFirestore.getInstance()
    private val prefs by lazy {
        context.getSharedPreferences("snake_scores", Context.MODE_PRIVATE)
    }

    // ── read ──────────────────────────────────────────────────────────────

    /** Returns the all-time high score for the current user (remote or local). */
    suspend fun getHighScore(): Int {
        val uid = auth.currentUser?.uid
        return if (uid != null) {
            try {
                val doc = db.collection("users").document(uid).get().await()
                doc.getLong("highScore")?.toInt() ?: 0
            } catch (e: Exception) {
                // Fall back to local if offline
                prefs.getInt("highScore_$uid", 0)
            }
        } else {
            prefs.getInt("guest_highScore", 0)
        }
    }

    // ── write ─────────────────────────────────────────────────────────────

    /**
     * Submits [score] only if it beats the stored high score.
     * Always writes locally as a cache; also writes to Firestore when logged in.
     */
    suspend fun submitScore(score: Int) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            val key = "highScore_$uid"
            val local = prefs.getInt(key, 0)
            if (score > local) {
                prefs.edit().putInt(key, score).apply()
                try {
                    db.collection("users").document(uid)
                        .set(mapOf("highScore" to score), SetOptions.merge())
                        .await()
                } catch (_: Exception) {
                    // Will stay in local cache; next submit will retry
                }
            }
        } else {
            val local = prefs.getInt("guest_highScore", 0)
            if (score > local) {
                prefs.edit().putInt("guest_highScore", score).apply()
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    fun isLoggedIn(): Boolean = auth.currentUser != null

    fun currentUserEmail(): String? = auth.currentUser?.email

    fun signOut() = auth.signOut()
}
