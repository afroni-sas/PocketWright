package com.afroni.pocketwright.messaging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * L'ordre d'arrivée des messages, avec un numéro unique pour SMS et MMS (leurs tables Android ont chacune leurs
 * identifiants). C'est ce numéro qui permet à un client de l'API de reprendre le flux là où il s'était arrêté.
 *
 * Seules les références sont ici : le contenu reste dans le stockage SMS d'Android, et un message supprimé par
 * l'utilisateur ne peut donc plus être rediffusé.
 */
class MessageJournal(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 2) {

    class Entry(val seq: Long, val kind: String, val messageId: Long, val date: Long)

    private val _latest = MutableStateFlow(readLatest())
    val latest: StateFlow<Long> = _latest.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE incoming (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                "message_id INTEGER NOT NULL, date INTEGER NOT NULL)",
        )
    }

    /** Seules des références : repartir de zéro ne perd aucun message. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS incoming")
        onCreate(db)
    }

    /**
     * Android réutilise les identifiants après une suppression : un même identifiant peut désigner deux messages
     * différents au fil du temps. La date, gardée ici, permet de reconnaître le bon à la relecture.
     */
    fun append(message: Message) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("kind", message.kind)
            put("message_id", message.id)
            put("date", message.date)
        }
        val seq = db.insert("incoming", null, values)
        if (seq <= 0) return
        db.delete("incoming", "seq <= ?", arrayOf((seq - KEEP).toString()))
        _latest.value = seq
    }

    fun after(seq: Long, limit: Int): List<Entry> =
        readableDatabase.query(
            "incoming", arrayOf("seq", "kind", "message_id", "date"), "seq > ?", arrayOf(seq.toString()), null, null, "seq", limit.toString(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))) }
        }

    private fun readLatest(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM incoming", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private companion object {
        const val KEEP = 5_000L
    }
}
