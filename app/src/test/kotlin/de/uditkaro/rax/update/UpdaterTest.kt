package de.uditkaro.rax.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private fun release(tag: String, apk: String? = "Rax-$tag.apk", draft: Boolean = false) = """
        {"tag_name": "$tag", "draft": $draft, "name": "x", "assets": [
            ${if (apk != null) """{"name": "$apk", "browser_download_url": "https://example.com/$apk", "size": 1000}""" else ""}
        ]}
    """

    @Test
    fun picksTheHighestPreviewWithAnApk() {
        val json = "[${release("preview-9")}, ${release("preview-11", apk = null)}, ${release("preview-12", draft = true)}, ${release("v2.0")}, ${release("preview-10")}]"
        assertEquals(Release(10, "https://example.com/Rax-preview-10.apk", 1000), newestPreview(json))
    }

    @Test
    fun noPreviewMeansNoUpdate() {
        assertNull(newestPreview("[]"))
        assertNull(newestPreview("[${release("v1.0")}]"))
    }
}
