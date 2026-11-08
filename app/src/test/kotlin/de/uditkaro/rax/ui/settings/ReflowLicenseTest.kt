package de.uditkaro.rax.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReflowLicenseTest {
    @Test
    fun handWrappedParagraphsAreJoined() {
        val text = """
                                 Apache License
                           Version 2.0, January 2004

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity.

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      -----------------------------------------------------------
      SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
      -----------------------------------------------------------

      Copyright (c) 2015 Mitchell Hashimoto
      Copyright (c) 2024 Someone Else
        """.trimIndent()
        val expected = """
            Apache License
            Version 2.0, January 2004

            "Legal Entity" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity.

            (b) You must cause any modified files to carry prominent notices stating that You changed the files; and

            -----------------------------------------------------------
            SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
            -----------------------------------------------------------

            Copyright (c) 2015 Mitchell Hashimoto
            Copyright (c) 2024 Someone Else
        """.trimIndent()
        assertEquals(expected, reflowLicense(text))
    }
}
