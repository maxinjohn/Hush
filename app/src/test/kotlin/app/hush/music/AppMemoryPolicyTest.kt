package app.hush.music

import coil3.request.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMemoryPolicyTest {
    @Test
    fun `low RAM devices get a smaller default image disk cache`() {
        val config = resolveImageDiskCacheConfig(maxImageCacheSizeMb = null, isLowRam = true)

        assertEquals(CachePolicy.ENABLED, config.policy)
        assertEquals(128L * 1024L * 1024L, config.maxSizeBytes)
    }

    @Test
    fun `explicit cache size is respected on normal devices`() {
        val config = resolveImageDiskCacheConfig(maxImageCacheSizeMb = 64, isLowRam = false)

        assertEquals(CachePolicy.ENABLED, config.policy)
        assertEquals(64L * 1024L * 1024L, config.maxSizeBytes)
    }

    @Test
    fun `zero disables image disk cache`() {
        val config = resolveImageDiskCacheConfig(maxImageCacheSizeMb = 0, isLowRam = true)

        assertEquals(CachePolicy.DISABLED, config.policy)
        assertTrue(config.maxSizeBytes > 0L)
    }

    @Test
    fun `negative value keeps unlimited cache compatibility`() {
        val config = resolveImageDiskCacheConfig(maxImageCacheSizeMb = -1, isLowRam = true)

        assertEquals(CachePolicy.ENABLED, config.policy)
        assertEquals(Long.MAX_VALUE, config.maxSizeBytes)
    }
}
