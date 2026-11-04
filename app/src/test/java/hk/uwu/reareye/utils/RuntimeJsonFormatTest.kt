package hk.uwu.reareye.utils

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeJsonFormatTest {
    @Test fun acceptsEmptyAndPopulatedArrays() {
        listOf("[]", " [ {\"resId\":\"test\",\"applyId\":\"one\"} ] \n")
            .forEach(RuntimeJsonFormat::validate)
    }

    @Test fun rejectsBlankTruncatedWrongRootAndTrailingData() {
        listOf("", " ", "[", "[{\"resId\":", "{}", "null", "0", "[] garbage", "[][]")
            .forEach { text -> assertThrows(IOException::class.java) { RuntimeJsonFormat.validate(text) } }
    }
}
