package com.kexu.app

import org.junit.Assume

/**
 * 真实课表测试样例（含姓名、学号等个人信息）不随公开仓库上传；
 * 克隆仓库后把 `cug_wh_timetable.pdf` / `.xlsx` / `cug_expected.csv` 放进
 * `app/src/test/resources/` 即可运行这些测试。缺少样例时测试会被跳过（skipped），不算失败。
 */
object TestFixtures {

    fun require(name: String): java.io.InputStream {
        val stream = TestFixtures::class.java.classLoader?.getResourceAsStream(name)
        Assume.assumeTrue(
            "缺少真实课表测试样例 $name（出于隐私不在公开仓库提供，可放入 app/src/test/resources 后运行）",
            stream != null
        )
        return stream!!
    }
}
