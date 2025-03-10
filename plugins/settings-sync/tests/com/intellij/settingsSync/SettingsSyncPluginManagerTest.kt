package com.intellij.settingsSync

import com.intellij.idea.TestFor
import com.intellij.openapi.components.SettingsCategory
import org.junit.Assert
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsSyncPluginManagerTest : BasePluginManagerTest() {

  @Test
  fun `test install missing plugins`() {
    pluginManager.pushChangesToIde(state {
      quickJump(enabled = false)
      typengo(enabled = true)
      ideaLight(enabled = true, category = SettingsCategory.UI)
    })

    val installedPluginIds = testPluginManager.installer.installedPluginIds
    // NB: quickJump should be skipped because it is disabled
    assertEquals(2, installedPluginIds.size)
    assertTrue(installedPluginIds.containsAll(listOf(typengo.pluginId, ideaLight.pluginId)))
  }

  @Test
  fun `test do not install when plugin sync is disabled`() {
    SettingsSyncSettings.getInstance().setCategoryEnabled(SettingsCategory.PLUGINS, false)
    try {
      pluginManager.pushChangesToIde(state {
        quickJump(enabled = false)
        typengo(enabled = true)
        ideaLight(enabled = true, category = SettingsCategory.UI)
      })

      val installedPluginIds = testPluginManager.installer.installedPluginIds
      // IdeaLight is a UI plugin, it doesn't fall under PLUGINS category
      assertEquals(1, installedPluginIds.size)
      assertTrue(installedPluginIds.contains(ideaLight.pluginId))
    }
    finally {
      SettingsSyncSettings.getInstance().setCategoryEnabled(SettingsCategory.PLUGINS, true)
    }
  }

  @Test
  fun `test do not install UI plugin when UI category is disabled`() {
    SettingsSyncSettings.getInstance().setCategoryEnabled(SettingsCategory.UI, false)
    try {
      pluginManager.pushChangesToIde(state {
        quickJump(enabled = false)
        typengo(enabled = true)
        ideaLight(enabled = true, category = SettingsCategory.UI)
      })

      val installedPluginIds = testPluginManager.installer.installedPluginIds
      // IdeaLight is a UI plugin, it doesn't fall under PLUGINS category
      assertEquals(1, installedPluginIds.size)
      assertTrue(installedPluginIds.contains(typengo.pluginId))
    }
    finally {
      SettingsSyncSettings.getInstance().setCategoryEnabled(SettingsCategory.UI, true)
    }
  }

  @Test
  fun `test disable installed plugin`() {
    testPluginManager.addPluginDescriptors(quickJump)
    pluginManager.updateStateFromIdeOnStart(null)

    assertPluginManagerState {
      quickJump(enabled = true)
    }

    pluginManager.pushChangesToIde(state {
      quickJump(enabled = false)
      typengo(enabled = true)
      ideaLight(enabled = true, category = SettingsCategory.UI)
    })

    assertFalse(quickJump.isEnabled)
    assertIdeState {
      quickJump(enabled = false)
      typengo(enabled = true)
      ideaLight(enabled = true, category = SettingsCategory.UI)
    }
  }

  @Test
  fun `test disable two plugins at once`() {
    // install two plugins
    testPluginManager.addPluginDescriptors(quickJump, typengo)

    pluginManager.pushChangesToIde(state {
      quickJump(enabled = false)
      typengo(enabled = false)
    })

    assertFalse(quickJump.isEnabled)
    assertFalse(typengo.isEnabled)
  }

  @Test
  fun `test update state from IDE`() {
    testPluginManager.addPluginDescriptors(quickJump, typengo, git4idea)

    pluginManager.updateStateFromIdeOnStart(null)

    assertPluginManagerState {
      quickJump(enabled = true)
      typengo(enabled = true)
    }

    testPluginManager.disablePlugin(git4idea.pluginId)

    assertPluginManagerState {
      quickJump(enabled = true)
      typengo(enabled = true)
      git4idea(enabled = false)
    }

    testPluginManager.disablePlugin(typengo.pluginId)
    testPluginManager.enablePlugin(git4idea.pluginId)

    assertPluginManagerState {
      quickJump(enabled = true)
      typengo(enabled = false)
    }
  }

  @Test
  fun `test do not remove entries about disabled plugins which are not installed`() {
    testPluginManager.addPluginDescriptors(typengo, git4idea)

    val savedState = state {
      quickJump(enabled = false)
      typengo(enabled = true)
      git4idea(enabled = true)
    }

    pluginManager.updateStateFromIdeOnStart(savedState)

    assertPluginManagerState {
      quickJump(enabled = false)
      typengo(enabled = true)
      // git4idea is removed because existing bundled enabled plugin is the default state
    }
  }

  @Test
  fun `test push settings to IDE`() {
    testPluginManager.addPluginDescriptors(quickJump, typengo, git4idea)
    pluginManager.updateStateFromIdeOnStart(null)

    pluginManager.pushChangesToIde(state {
      quickJump(enabled = false)
      git4idea(enabled = false)
    })

    assertIdeState {
      quickJump(enabled = false)
      typengo(enabled = true)
      git4idea(enabled = false)
    }

    pluginManager.pushChangesToIde(state {
      quickJump(enabled = false)
    })
    // no entry for the bundled git4idea plugin => it is enabled

    assertIdeState {
      quickJump(enabled = false)
      typengo(enabled = true)
      git4idea(enabled = true)
    }
  }

  @Test
  @TestFor(issues = ["IDEA-313300"])
  fun `test update removed from IDE on start`() {
    quickJump.isEnabled = false
    testPluginManager.addPluginDescriptors(typengo, quickJump)

    val savedState = state {
      quickJump(enabled = false)
      typengo(enabled = true)
      ideaLight(enabled = true)
    }

    pluginManager.updateStateFromIdeOnStart(savedState)

    assertPluginManagerState {
      quickJump(enabled = false)
      typengo(enabled = true)
      ideaLight(enabled = false) // ideaLight is disabled because it was removed manually (from disk)
    }
  }

  @Test
  @TestFor(issues = ["IDEA-314934"])
  fun `test don't disable effective essential plugins`() {
    testPluginManager.addPluginDescriptors(javascript, css)
    pluginManager.updateStateFromIdeOnStart(null)

    pluginManager.pushChangesToIde(state {
      css(enabled = false)
    })

    assertIdeState {
      css(enabled = true) // IDE state shouldn't have changed (css is an essential plugin)
      javascript(enabled = true) // IDE state shouldn't have changed (css is an essential plugin)
    }
    assertPluginManagerState {
      css(enabled = false) // we shouldn't enable it in PluginManager
    }
  }

  @Test
  @TestFor(issues = ["IDEA-314934"])
  fun `test don't disable essential plugins`() {
    testPluginManager.addPluginDescriptors(javascript)
    pluginManager.updateStateFromIdeOnStart(null)

    pluginManager.pushChangesToIde(state {
      javascript(enabled = false)
    })

    assertIdeState {
      javascript(enabled = true) // IDE state shouldn't have changed (css is an essential plugin)
    }
    assertPluginManagerState {
      javascript(enabled = false) // we shouldn't enable it in PluginManager
    }
  }

  @Test
  @TestFor(issues = ["IDEA-303581"])
  fun `test don't fail the sync on plugins' action fail`() {
    testPluginManager.addPluginDescriptors(git4idea, quickJump.withEnabled(false))
    pluginManager.updateStateFromIdeOnStart(null)

    testPluginManager.pluginStateExceptionThrower = {
      if (it == git4idea.pluginId)
        throw RuntimeException("Some arbitrary exception")
    }

    val pushedState = state {
      git4idea(enabled = false)
      quickJump(enabled = true)
      typengo(enabled = true)
    }

    pluginManager.pushChangesToIde(pushedState)

    assertIdeState(pushedState)
    assertPluginManagerState(pushedState)
  }

  @Test
  @TestFor(issues = ["IDEA-303581"])
  fun `test don't fail the sync when plugin install fails`() {
    testPluginManager.addPluginDescriptors(git4idea, quickJump.withEnabled(false))
    pluginManager.updateStateFromIdeOnStart(null)

    testPluginManager.pluginStateExceptionThrower = {
      if (it == git4idea.pluginId)
        throw RuntimeException("Some arbitrary exception")
    }

    val pushedState = state {
      git4idea(enabled = false)
      quickJump(enabled = true)
      typengo(enabled = true)
    }

    pluginManager.pushChangesToIde(pushedState)

    assertIdeState(pushedState)
    assertPluginManagerState(pushedState)
  }

  @Test
  @TestFor(issues = ["IDEA-303581"])
  fun `turn-off sync of plugin that fails to install`() {
    testPluginManager.addPluginDescriptors(git4idea)
    pluginManager.updateStateFromIdeOnStart(null)

    testPluginManager.installer.installPluginExceptionThrower = {
      if (it == quickJump.pluginId)
        throw RuntimeException("Some arbitrary install exception")
    }

    val pushedState = state {
      git4idea(enabled = false)
      quickJump(enabled = true)
      typengo(enabled = true)
    }

    pluginManager.pushChangesToIde(pushedState)

    assertIdeState{
      git4idea(enabled = false)
      typengo(enabled = true)
    }
    assertPluginManagerState(pushedState)
    Thread.sleep(100)
    assertFalse(SettingsSyncSettings.getInstance().isSubcategoryEnabled(SettingsCategory.PLUGINS, quickJump.idString))
  }
}