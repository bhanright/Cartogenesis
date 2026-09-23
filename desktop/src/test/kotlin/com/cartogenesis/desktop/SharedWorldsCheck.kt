package com.cartogenesis.desktop

import com.cartogenesis.worldgen.SharedWorlds
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * [SharedWorlds.Check] for this module's runner: `@ExtendWith(SharedWorldsCheck::class)` on a class
 * that borrows shared worlds names each test as the borrower and checks, after it, that none of the
 * worlds the class has borrowed was written to.
 *
 * The JUnit 4 rule does nothing here, because this module runs on the JUnit Platform, whose tests
 * take extensions rather than rules.
 */
class SharedWorldsCheck : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        SharedWorlds.lender.beginTest(context.requiredTestClass.name, borrowerOf(context))
    }

    override fun afterEach(context: ExtensionContext) {
        SharedWorlds.lender.endTest(context.requiredTestClass.name, borrowerOf(context))
    }

    private fun borrowerOf(context: ExtensionContext): String =
        "${context.requiredTestClass.name}.${context.requiredTestMethod.name}"
}
