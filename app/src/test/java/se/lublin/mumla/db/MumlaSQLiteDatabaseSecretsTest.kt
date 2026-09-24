package se.lublin.mumla.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secrets at rest, with an in-memory AES key standing in for the Android Keystore. */
@RunWith(RobolectricTestRunner::class)
class MumlaSQLiteDatabaseSecretsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "secrets-test.db"
    private val opened = mutableListOf<SQLiteOpenHelper>()

    /** AES-GCM with a key that lives only in this object. */
    private class FakeCipher : SecretCipher {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private object BrokenCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = throw GeneralSecurityException("no keystore")
        override fun decrypt(sealed: ByteArray): ByteArray = throw GeneralSecurityException("no keystore")
    }

    private val cipher = FakeCipher()

    private fun open(with: SecretCipher = cipher) = MumlaSQLiteDatabase(context, name, null, with).also { opened += it }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    private fun raw(sql: String): List<Any?> = open().readableDatabase.rawQuery(sql, null).use { c ->
        buildList {
            while (c.moveToNext()) add(if (c.getType(0) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(0) else c.getString(0))
        }
    }

    private val p12 = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x02, 0x03, 0x04)

    @Test
    fun secretsRoundTripAndAreNotStoredInPlainText() {
        val db = open()
        val server = Server(-1, "s", "s.example", 64738, "me", "hunter2").also { db.addServer(it) }
        db.addAccessToken(server.id, "tok")
        val cert = db.addCertificate("me.p12", p12)

        assertThat(db.getServers().single().password).isEqualTo("hunter2")
        assertThat(db.getAccessTokens(server.id)).containsExactly("tok")
        assertThat(db.getCertificateData(cert.id)).isEqualTo(p12)

        val storedPassword = raw("SELECT password FROM server").single() as String
        assertThat(storedPassword).doesNotContain("hunter2")
        assertThat(SecretCodec.isSealed(storedPassword)).isTrue()
        assertThat(raw("SELECT value FROM tokens").single() as String).doesNotContain("tok")
        assertThat(SecretCodec.isSealed(raw("SELECT data FROM certificates").single() as ByteArray)).isTrue()
    }

    @Test
    fun aNullPasswordStaysNull() {
        val db = open()
        db.addServer(Server(-1, "s", "s.example", 64738, "me", null))
        assertThat(db.getServers().single().password).isNull()
    }

    @Test
    fun removingATokenFindsItDespiteRandomizedEncryption() {
        val db = open()
        db.addAccessToken(1, "a")
        db.addAccessToken(1, "b")
        db.addAccessToken(1, "a")
        db.addAccessToken(2, "a")

        db.removeAccessToken(1, "a")

        assertThat(db.getAccessTokens(1)).containsExactly("b")
        assertThat(db.getAccessTokens(2)).containsExactly("a")
    }

    @Test
    fun plainTextRowsFromBeforeEncryptionAreStillRead() {
        val db = open()
        val w = db.writableDatabase
        w.insert("server", null, ContentValues().apply {
            put("name", "old"); put("host", "old.example"); put("port", 64738); put("username", "me"); put("password", "legacy")
        })
        w.insert("tokens", null, ContentValues().apply { put("server", 1); put("value", "oldtok") })
        val id = w.insert("certificates", null, ContentValues().apply { put("name", "old.p12"); put("data", p12) })

        assertThat(db.getServers().single().password).isEqualTo("legacy")
        assertThat(db.getAccessTokens(1)).containsExactly("oldtok")
        assertThat(db.getCertificateData(id)).isEqualTo(p12)
        db.removeAccessToken(1, "oldtok")
        assertThat(db.getAccessTokens(1)).isEmpty()
    }

    @Test
    fun upgradingEncryptsExistingPlainTextSecrets() {
        // A version 8 database as the previous release wrote it.
        object : SQLiteOpenHelper(context, name, null, MumlaSQLiteDatabase.PRE_ENCRYPTED_SECRETS_DB_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(MumlaSQLiteDatabase.TABLE_SERVER_CREATE_SQL)
                db.execSQL(MumlaSQLiteDatabase.TABLE_TOKENS_CREATE_SQL)
                db.execSQL(MumlaSQLiteDatabase.TABLE_CERTIFICATES_CREATE_SQL)
                db.execSQL(MumlaSQLiteDatabase.TABLE_FAVOURITES_CREATE_SQL)
                db.execSQL(MumlaSQLiteDatabase.TABLE_COMMENTS_CREATE_SQL)
                db.execSQL(MumlaSQLiteDatabase.TABLE_LOCAL_MUTE_CREATE_SQL)
                db.execSQL(MumlaSQLiteDatabase.TABLE_LOCAL_IGNORE_CREATE_SQL)
                db.insert("server", null, ContentValues().apply {
                    put("name", "old"); put("host", "old.example"); put("port", 64738); put("username", "me"); put("password", "legacy")
                })
                db.insert("server", null, ContentValues().apply {
                    put("name", "nopw"); put("host", "n.example"); put("port", 64738); put("username", "me")
                })
                db.insert("tokens", null, ContentValues().apply { put("server", 1); put("value", "oldtok") })
                db.insert("certificates", null, ContentValues().apply { put("name", "old.p12"); put("data", p12) })
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.also { opened += it }.writableDatabase.close()

        val db = open()

        assertThat(db.getServers().map { it.password }).containsExactly("legacy", null)
        assertThat(db.getAccessTokens(1)).containsExactly("oldtok")
        assertThat(db.getCertificateData(db.getCertificates().single().id)).isEqualTo(p12)
        assertThat(raw("SELECT password FROM server WHERE password IS NOT NULL").map { SecretCodec.isSealed(it as String) })
            .containsExactly(true)
        assertThat(SecretCodec.isSealed(raw("SELECT value FROM tokens").single() as String)).isTrue()
        assertThat(SecretCodec.isSealed(raw("SELECT data FROM certificates").single() as ByteArray)).isTrue()
    }

    @Test
    fun secretsSealedUnderAnotherKeyReadAsAbsent() {
        val db = open()
        val server = Server(-1, "s", "s.example", 64738, "me", "hunter2").also { db.addServer(it) }
        db.addAccessToken(server.id, "tok")
        val cert = db.addCertificate("me.p12", p12)
        db.close()

        val elsewhere = open(FakeCipher())

        assertThat(elsewhere.getServers().single().password).isNull()
        assertThat(elsewhere.getAccessTokens(server.id)).isEmpty()
        assertThat(elsewhere.getCertificateData(cert.id)).isNull()
        assertThat(elsewhere.getCertificates().map { it.name }).containsExactly("me.p12")
    }

    @Test
    fun withoutAWorkingKeySecretsAreKeptInPlainTextRatherThanLost() {
        val db = open(BrokenCipher)
        val server = Server(-1, "s", "s.example", 64738, "me", "hunter2").also { db.addServer(it) }
        db.addAccessToken(server.id, "tok")
        val cert = db.addCertificate("me.p12", p12)

        assertThat(db.getServers().single().password).isEqualTo("hunter2")
        assertThat(db.getAccessTokens(server.id)).containsExactly("tok")
        assertThat(db.getCertificateData(cert.id)).isEqualTo(p12)
    }
}
