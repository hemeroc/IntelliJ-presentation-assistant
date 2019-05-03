/*
 * Copyright 2000-2016 Nikolay Chashnikov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author nik
 */
package org.nik.presentationAssistant

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.MacKeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.ArrayList
import java.util.LinkedHashMap
import javax.swing.JFrame
import javax.swing.KeyStroke

class ShortcutPresenter : Disposable {
    private val movingActions = setOf(
            "EditorLeft", "EditorRight", "EditorDown", "EditorUp",
            "EditorLineStart", "EditorLineEnd", "EditorPageUp", "EditorPageDown",
            "EditorPreviousWord", "EditorNextWord",
            "EditorScrollUp", "EditorScrollDown",
            "EditorTextStart", "EditorTextEnd",
            "EditorDownWithSelection", "EditorUpWithSelection",
            "EditorRightWithSelection", "EditorLeftWithSelection",
            "EditorLineStartWithSelection", "EditorLineEndWithSelection",
            "EditorPageDownWithSelection", "EditorPageUpWithSelection")

    private val typingActions = setOf(IdeActions.ACTION_EDITOR_BACKSPACE, IdeActions.ACTION_EDITOR_ENTER,
            IdeActions.ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE)
    private val parentGroupIds = setOf("CodeCompletionGroup", "FoldingGroup", "GoToMenu", "IntroduceActionsGroup")
    private var infoPanel: ActionInfoPanel? = null
    private var lastOpenedProject: Project? = null
    private val parentNames by lazy(::loadParentNames)
    private val windowAdapters = mutableMapOf<JFrame, WindowAdapter>()

    init {
        enable()
    }

    private fun Project?.addProjectWindowListener() {
        if (this == null) return
        val frame = WindowManager.getInstance().getFrame(this) ?: return
        val newWindowAdapter = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                showProjectInfo(this@addProjectWindowListener)
            }
        }
        windowAdapters.compute(frame) { _, oldWindowAdapter ->
            frame.removeWindowListener(oldWindowAdapter)
            frame.addWindowFocusListener(newWindowAdapter)
            newWindowAdapter
        }
    }

    private fun Project?.removeProjectWindowListener() {
        if (this == null) return
        val frame = WindowManager.getInstance().getFrame(this) ?: return
        windowAdapters.remove(frame)?.run {
            frame.removeWindowListener(this)
        }
    }

    private fun enable() {
        ProjectManager.getInstance().openProjects.forEach {
            it.addProjectWindowListener()
        }
        ApplicationManager.getApplication().messageBus.connect()
                .subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
                    override fun applicationDeactivated(ideFrame: IdeFrame?) { lastOpenedProject = null }
                })
        ApplicationManager.getApplication().messageBus.connect()
                .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                    override fun projectOpened(project: Project?) = project.addProjectWindowListener()
                    override fun projectClosed(project: Project?) = project.removeProjectWindowListener()
                })
        ActionManager.getInstance().addAnActionListener(object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent?) {
                val actionId = ActionManager.getInstance().getId(action) ?: return

                if (!movingActions.contains(actionId) && !typingActions.contains(actionId) && event != null) {
                    val project = event.project
                    val text = event.presentation.text
                    showActionInfo(ActionData(actionId, project, text))
                }
            }

            override fun beforeEditorTyping(c: Char, dataContext: DataContext?) {}
        }, this)
    }

    private fun loadParentNames(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val actionManager = ActionManager.getInstance()
        for (groupId in parentGroupIds) {
            val group = actionManager.getAction(groupId)
            if (group is ActionGroup) {
                fillParentNames(group, group.getTemplatePresentation().text!!, result)
            }
        }
        return result
    }

    private fun fillParentNames(group: ActionGroup, parentName: String, parentNames: MutableMap<String, String>) {
        val actionManager = ActionManager.getInstance()
        for (item in group.getChildren(null)) {
            when (item) {
                is ActionGroup -> {
                    if (!item.isPopup) fillParentNames(item, parentName, parentNames)
                }
                else -> {
                    val id = actionManager.getId(item)
                    if (id != null) {
                        parentNames[id] = parentName
                    }
                }
            }
        }

    }

    class ActionData(val actionId: String, val project: Project?, val actionText: String?)

    private fun MutableList<Pair<String, Font?>>.addText(text: String) {
        this.add(Pair(text, null))
    }

    private fun showProjectInfo(project: Project) {
        if (!project.isDisposed && project.isOpen && lastOpenedProject != project) {
            val fragments = ArrayList<Pair<String, Font?>>()
            lastOpenedProject = project
            fragments.addText("<b>Project: </b>${project.name}")
            if (infoPanel == null || !infoPanel!!.canBeReused(project)) {
                infoPanel = ActionInfoPanel(project, fragments)
            } else {
                infoPanel!!.updateText(project, fragments)
            }
        }
    }

    fun showActionInfo(actionData: ActionData) {
        val actionId = actionData.actionId
        val parentGroupName = parentNames[actionId]
        val actionText = (if (parentGroupName != null) "$parentGroupName ${MacKeymapUtil.RIGHT} " else "") + (actionData.actionText ?: "").removeSuffix("...")

        val fragments = ArrayList<Pair<String, Font?>>()
        if (actionText.isNotEmpty()) {
            fragments.addText("<b>$actionText</b>")
        }

        val mainKeymap = getPresentationAssistant().configuration.mainKeymap
        val shortcutTextFragments = shortcutTextFragments(mainKeymap, actionId, actionText)
        if (shortcutTextFragments.isNotEmpty()) {
            if (fragments.isNotEmpty()) fragments.addText(" via&nbsp;")
            fragments.addAll(shortcutTextFragments)
        }

        val alternativeKeymap = getPresentationAssistant().configuration.alternativeKeymap
        if (alternativeKeymap != null) {
            val mainShortcut = shortcutText(mainKeymap.getKeymap()?.getShortcuts(actionId), mainKeymap.getKind())
            val altShortcutTextFragments = shortcutTextFragments(alternativeKeymap, actionId, mainShortcut)
            if (altShortcutTextFragments.isNotEmpty()) {
                fragments.addText("&nbsp;(")
                fragments.addAll(altShortcutTextFragments)
                fragments.addText(")")
            }
        }

        val realProject = actionData.project ?: ProjectManager.getInstance().openProjects.firstOrNull()
        if (realProject != null && !realProject.isDisposed && realProject.isOpen) {
            if (infoPanel == null || !infoPanel!!.canBeReused(realProject)) {
                infoPanel = ActionInfoPanel(realProject, fragments)
            } else {
                infoPanel!!.updateText(realProject, fragments)
            }
        }
    }

    private fun shortcutTextFragments(keymap: KeymapDescription, actionId: String, shownShortcut: String): List<Pair<String, Font?>> {
        val fragments = ArrayList<Pair<String, Font?>>()
        val shortcutText = shortcutText(keymap.getKeymap()?.getShortcuts(actionId), keymap.getKind())
        if (shortcutText.isEmpty() || shortcutText == shownShortcut) return fragments

        when {
            keymap.getKind() == KeymapKind.WIN || SystemInfo.isMac -> {
                fragments.addText(shortcutText)
            }
            macKeyStrokesFont != null && macKeyStrokesFont!!.canDisplayUpTo(shortcutText) == -1 -> {
                fragments.add(Pair(shortcutText, macKeyStrokesFont))
            }
            else -> {
                val altShortcutAsWin = shortcutText(keymap.getKeymap()?.getShortcuts(actionId), KeymapKind.WIN)
                if (altShortcutAsWin.isNotEmpty() && shownShortcut != altShortcutAsWin) {
                    fragments.addText(altShortcutAsWin)
                }
            }
        }
        val keymapText = keymap.displayText
        if (keymapText.isNotEmpty()) {
            fragments.addText("&nbsp;$keymapText")
        }
        return fragments
    }

    private fun shortcutText(shortcuts: Array<Shortcut>?, keymapKind: KeymapKind) =
        when {
            shortcuts == null || shortcuts.isEmpty() -> ""
            else -> shortcutText(shortcuts[0], keymapKind)
        }

    private fun shortcutText(shortcut: Shortcut, keymapKind: KeymapKind) =
        when (shortcut) {
            is KeyboardShortcut -> arrayOf(shortcut.firstKeyStroke, shortcut.secondKeyStroke).filterNotNull().joinToString(separator = ", ") { shortcutText(it, keymapKind) }
            else -> ""
        }

    private fun shortcutText(keystroke: KeyStroke, keymapKind: KeymapKind) =
        when (keymapKind) {
            KeymapKind.MAC -> MacKeymapUtil.getKeyStrokeText(keystroke)
            KeymapKind.WIN -> {
                val modifiers = keystroke.modifiers
                val tokens = arrayOf(
                   if (modifiers > 0) getWinModifiersText(modifiers) else null,
                   getWinKeyText(keystroke.keyCode)
                )
                tokens.filterNotNull().filter { it.isNotEmpty() }.joinToString(separator = "+").trim()
            }
    }

    fun disable() {
        windowAdapters.forEach { frame, windowAdapter ->
            frame.removeWindowFocusListener(windowAdapter)
            windowAdapters.remove(frame)
        }
        lastOpenedProject = null
        if (infoPanel != null) {
            infoPanel!!.close()
            infoPanel = null
        }
        Disposer.dispose(this)
    }

    override fun dispose() {
    }
}