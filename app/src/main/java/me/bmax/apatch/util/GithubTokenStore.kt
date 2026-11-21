package me.bmax.apatch.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import me.bmax.apatch.dsh.KernelEnv
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub 令牌的加密存储。
 *
 * ## 为什么需要它
 *
 * 匿名调用 GitHub API 限流是 **60 次/小时/IP**（按 IP 算，不是按设备）。更新检查、
 * 运行时版本列表、扩展商店索引全都走它，用户点几下就可能撞上 403 —— 表现是
 * 「检查更新失败」，而真实原因只是被限流了，从界面上完全看不出来。
 *
 * 带上令牌之后限额变成 5000 次/小时，这个问题基本消失。
 *
 * ## 为什么不能明文存
 *
 * 令牌等价于账号的读写凭据（取决于 scope）。明文放 SharedPreferences 意味着：
 *  - root 设备上任何应用可读（shared_prefs 目录下的 XML）；
 *  - 备份/迁移时会被一起带走（备份文件经常被随手分享）。
 *
 * 所以这里用 **AndroidKeyStore**：密钥由系统 TEE/StrongBox 持有，**永远不出安全硬件**，
 * 应用只能请求它做加解密。拿到 SharedPreferences 文件的人没有密钥，密文就是随机字节。
 *
 * ## 用 GCM 而不是 CBC
 *
 * GCM 自带完整性校验：密文被改过会在解密时抛异常，而不是解出一段垃圾再拿去发请求。
 * IV 每次随机生成并随密文一起存（前 12 字节），这是 GCM 的硬要求 —— 重用 IV 会
 * 直接摧毁 GCM 的安全性。
 *
 * ## 令牌本身不校验格式
 *
 * 这里只做「非空 + 去掉空白」。GitHub 的令牌格式变过几次（ghp_ / github_pat_ / 老式
 * 40 位十六进制），写死前缀校验只会在下次变格式时把合法令牌判成非法。
 */
object GithubTokenStore {

    private const val TAG = "GithubTokenStore"

    /** prefs 键：密文（Base64）。 */
    private const val KEY_CIPHERTEXT = "github_token_ciphertext"

    /** prefs 键：IV（Base64，12 字节）。 */
    private const val KEY_IV = "github_token_iv"

    /** KeyStore 里的别名。改名会让已存的令牌全部解不开，所以不要动。 */
    private const val KEYSTORE_ALIAS = "dsh_github_token"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val GCM_TAG_BITS = 128

    private fun prefs(context: Context): SharedPreferences =
        runCatching { KernelEnv.hostPrefs }
            .getOrElse { context.applicationContext.getSharedPreferences(KernelEnv.hostPrefsName, Context.MODE_PRIVATE) }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** 取出（必要时创建）密钥。密钥只在首次写入时生成。 */
    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求用户认证：更新检查可能在后台跑，要求解锁会让它静默失败。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * 存令牌。传空串等于清除。
     *
     * @return true = 已写入；false = 加密失败（此时**不动**已存的旧值，
     *   不能因为这次写失败就把用户原来的令牌抹掉）。
     */
    fun save(context: Context, token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            clear(context)
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            prefs(context).edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            true
        }.getOrElse {
            // 不记令牌本身，只记失败原因：日志会被用户贴到 issue 里。
            Log.w(TAG, "保存 GitHub 令牌失败: " + it.javaClass.simpleName)
            false
        }
    }

    /**
     * 读令牌。没有、解密失败（换了设备/密钥被清）都返回 null。
     *
     * 解密失败时**顺手清掉密文**：留着也解不开，下次读还要再失败一遍，
     * 而界面上会一直显示"已配置"却用不了 —— 那比直接显示未配置更糟。
     */
    fun load(context: Context): String? {
        val p = prefs(context)
        val ct = p.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = p.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "解不开已存的 GitHub 令牌（密钥可能已失效），已清除")
            clear(context)
            null
        }
    }

    /** 是否已配置令牌。**不触发解密**：列表/设置项只要一个布尔。 */
    fun hasToken(context: Context): Boolean =
        prefs(context).getString(KEY_CIPHERTEXT, null) != null

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    /**
     * 令牌的展示形式：只留头尾，中间打码。
     *
     * 设置页要显示"当前配置的是什么"，但**不能**把完整令牌画在屏幕上 ——
     * 截个图发出去就等于泄露了。
     */
    fun masked(context: Context): String? {
        val t = load(context) ?: return null
        if (t.length <= 10) return "••••"
        return t.take(4) + "••••" + t.takeLast(4)
    }
}
