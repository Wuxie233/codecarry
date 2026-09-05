package dev.wuxie233.codecarry.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CodexThreadNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun childGetsIndependentStateOwnerAndBackRestoresParent() {
        lateinit var controller: NavHostController
        rule.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = "home") {
                composable("home") { Text("Home") }
                composable(
                    "codex_chat?serverId={serverId}&threadId={threadId}",
                    arguments = listOf(
                        navArgument("serverId") { type = NavType.StringType },
                        navArgument("threadId") { type = NavType.StringType },
                    ),
                ) { entry -> Text(entry.arguments?.getString("threadId").orEmpty()) }
            }
        }
        rule.runOnIdle {
            controller.navigate(Screen.CodexChat.createRoute("server-a", "parent"))
        }
        var parentEntryId = ""
        lateinit var parentModel: ThreadIdentityModel
        rule.runOnIdle {
            parentEntryId = controller.currentBackStackEntry!!.id
            parentModel = threadModel(controller)
            assertEquals("parent", parentModel.threadId)
            controller.currentBackStackEntry!!.savedStateHandle["draft"] = "parent draft"
            controller.openCodexRelatedThread("server-a", "parent", "child")
        }
        rule.runOnIdle {
            val child = controller.currentBackStackEntry!!
            assertNotEquals(parentEntryId, child.id)
            val childModel = threadModel(controller)
            assertNotEquals(parentModel, childModel)
            assertEquals("child", childModel.threadId)
            assertEquals("child", child.arguments?.getString("threadId"))
            assertEquals("server-a", child.arguments?.getString("serverId"))
            assertEquals(null, child.savedStateHandle.get<String>("draft"))
            controller.openCodexRelatedThread("server-a", "child", "child")
            assertEquals(child.id, controller.currentBackStackEntry!!.id)
            controller.openCodexRelatedThread("server-a", "child", " ")
            assertEquals(child.id, controller.currentBackStackEntry!!.id)
            assertTrue(controller.popBackStack())
        }
        rule.runOnIdle {
            assertEquals(parentEntryId, controller.currentBackStackEntry!!.id)
            assertEquals(parentModel, threadModel(controller))
            assertEquals("parent draft", controller.currentBackStackEntry!!.savedStateHandle.get<String>("draft"))
        }
    }

    private fun threadModel(controller: NavHostController): ThreadIdentityModel {
        val entry = controller.currentBackStackEntry!!
        return ViewModelProvider(entry, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ThreadIdentityModel(entry.arguments!!.getString("threadId")!!) as T
        })[ThreadIdentityModel::class.java]
    }

    private class ThreadIdentityModel(val threadId: String) : ViewModel()
}
