// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.terminal;

import com.google.common.base.Predicate;
import com.intellij.ide.dnd.DnDDropHandler;
import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDSupport;
import com.intellij.ide.dnd.TransferableWrapper;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.EditorTabbedContainer;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.docking.DockManager;
import com.intellij.ui.docking.DragSession;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.TabsListener;
import com.intellij.ui.tabs.impl.JBEditorTabs;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.ui.tabs.impl.TabLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jediterm.terminal.ui.AbstractTabbedTerminalWidget;
import com.jediterm.terminal.ui.AbstractTabs;
import com.jediterm.terminal.ui.TerminalAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.vfs.TerminalSessionVirtualFileImpl;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;

/**
 * @author traff
 */
public class JBTabbedTerminalWidget extends AbstractTabbedTerminalWidget<JBTabInnerTerminalWidget> implements Disposable {

  private final Project myProject;
  private final JBTerminalSystemSettingsProviderBase mySettingsProvider;
  private final Function<String, JBTabInnerTerminalWidget> myCreateNewSessionAction;
  private final Disposable myParent;

  public JBTabbedTerminalWidget(@NotNull Project project,
                                @NotNull JBTerminalSystemSettingsProviderBase settingsProvider,
                                final @NotNull Function<Pair<AbstractTabbedTerminalWidget<JBTabInnerTerminalWidget>, String>, JBTabInnerTerminalWidget> createNewSessionAction, @NotNull Disposable parent) {
    super(settingsProvider, input -> createNewSessionAction.apply(Pair.create(input, null)));
    myProject = project;

    mySettingsProvider = settingsProvider;
    myCreateNewSessionAction = s -> createNewSessionAction.apply(Pair.create(this, s));
    myParent = parent;

    convertActions(this, getActions());

    Disposer.register(parent, this);
    Disposer.register(this, settingsProvider);

    DnDSupport.createBuilder(this).setDropHandler(new DnDDropHandler() {
                                                    @Override
                                                    public void drop(DnDEvent event) {
                                                      if (event.getAttachedObject() instanceof TransferableWrapper) {
                                                        TransferableWrapper ao = (TransferableWrapper)event.getAttachedObject();
                                                        if (ao != null &&
                                                            ao.getPsiElements() != null &&
                                                            ao.getPsiElements().length == 1 &&
                                                            ao.getPsiElements()[0] instanceof PsiFileSystemItem) {
                                                          PsiFileSystemItem element = (PsiFileSystemItem)ao.getPsiElements()[0];
                                                          PsiDirectory dir = element instanceof PsiFile ? ((PsiFile)element).getContainingDirectory() : (PsiDirectory)element;

                                                          createNewSessionAction.apply(Pair.create(JBTabbedTerminalWidget.this, dir.getVirtualFile().getPath()));
                                                        }
                                                      }
                                                    }
                                                  }

    ).install();
  }

  public static void convertActions(@NotNull JComponent component,
                                    @NotNull List<TerminalAction> actions) {
    convertActions(component, actions, null);
  }

  public static void convertActions(@NotNull JComponent component,
                                    @NotNull List<TerminalAction> actions,
                                    @Nullable final Predicate<KeyEvent> elseAction) {
    for (final TerminalAction action : actions) {
      if (action.isHidden()) {
        continue;
      }
      DumbAwareAction.create(e -> {
        KeyEvent event = e.getInputEvent() instanceof KeyEvent ? (KeyEvent)e.getInputEvent() : null;
        if (!action.perform(event)) {
          if (elseAction != null) {
            elseAction.apply(event);
          }
        }
      }).registerCustomShortcutSet(action.getKeyCode(), action.getModifiers(), component);
    }
  }

  @Override
  public JBTabInnerTerminalWidget createInnerTerminalWidget() {
    JBTabInnerTerminalWidget widget = new JBTabInnerTerminalWidget(myProject, mySettingsProvider, myParent, myCreateNewSessionAction, this);

    convertActions(widget, widget.getActions());
    convertActions(widget.getTerminalPanel(), widget.getTerminalPanel().getActions(), input -> {
      widget.getTerminalPanel().handleKeyEvent(input);
      return true;
    });

    return widget;
  }

  @Override
  protected JBTerminalTabs createTabbedPane() {
    return new JBTerminalTabs(myProject, myParent);
  }

  public class JBTerminalTabs implements AbstractTabs<JBTabInnerTerminalWidget> {
    private final JBEditorTabs myTabs;

    private final TabInfo.DragOutDelegate myDragDelegate = new MyDragOutDelegate();

    private final CopyOnWriteArraySet<TabChangeListener> myListeners = new CopyOnWriteArraySet<>();

    public JBTerminalTabs(@NotNull Project project, @NotNull Disposable parent) {
      final ActionManager actionManager = ActionManager.getInstance();
      myTabs = new JBEditorTabs(project, actionManager, IdeFocusManager.getInstance(project), parent) {
        @Override
        protected TabLabel createTabLabel(TabInfo info) {
          return new TerminalTabLabel(this, info);
        }
      };

      myTabs.addListener(new TabsListener() {
        @Override
        public void selectionChanged(TabInfo oldSelection, TabInfo newSelection) {
          for (TabChangeListener each : myListeners) {
            each.selectionChanged();
          }
        }

        @Override
        public void tabRemoved(@NotNull TabInfo tabInfo) {
          for (TabChangeListener each : myListeners) {
            each.tabRemoved();
          }
        }
      });

      myTabs.setTabDraggingEnabled(true);
    }

    @Override
    public int getSelectedIndex() {
      return myTabs.getIndexOf(myTabs.getSelectedInfo());
    }

    @Override
    public void setSelectedIndex(int index) {
      myTabs.select(myTabs.getTabAt(index), true);
    }

    @Override
    public void setTabComponentAt(int index, Component component) {
      //nop
    }

    @Override
    public int indexOfComponent(Component component) {
      for (int i = 0; i < myTabs.getTabCount(); i++) {
        if (component.equals(myTabs.getTabAt(i).getComponent())) {
          return i;
        }
      }

      return -1;
    }

    @Override
    public int indexOfTabComponent(Component component) {
      return 0; //nop
    }


    private TabInfo getTabAt(int index) {
      checkIndex(index);
      return myTabs.getTabAt(index);
    }

    private void checkIndex(int index) {
      if (index < 0 || index >= getTabCount()) {
        throw new ArrayIndexOutOfBoundsException("tabCount=" + getTabCount() + " index=" + index);
      }
    }


    @Override
    public JBTabInnerTerminalWidget getComponentAt(int i) {
      return (JBTabInnerTerminalWidget)getTabAt(i).getComponent();
    }

    @Override
    public void addChangeListener(TabChangeListener listener) {
      myListeners.add(listener);
    }

    @Override
    public void setTitleAt(int index, String title) {
      getTabAt(index).setText(title);
    }

    @Override
    public void setSelectedComponent(JBTabInnerTerminalWidget terminal) {
      TabInfo info = myTabs.findInfo(terminal);
      if (info != null) {
        myTabs.select(info, true);
      }
    }

    @Override
    public JComponent getComponent() {
      return myTabs.getComponent();
    }

    @Override
    public int getTabCount() {
      return myTabs.getTabCount();
    }

    @Override
    public void addTab(String name, JBTabInnerTerminalWidget terminal) {
      myTabs.addTab(createTabInfo(name, terminal));
      myTabs.updateUI();
    }

    private TabInfo createTabInfo(String name, JBTabInnerTerminalWidget terminal) {
      TabInfo tabInfo = new TabInfo(terminal).setText(name).setDragOutDelegate(myDragDelegate);
      return tabInfo
        .setObject(new TerminalSessionVirtualFileImpl(tabInfo, terminal, mySettingsProvider));
    }

    @Override
    public String getTitleAt(int i) {
      return getTabAt(i).getText();
    }

    @Override
    public void removeAll() {
      myTabs.removeAllTabs();
    }

    @Override
    public void remove(JBTabInnerTerminalWidget terminal) {
      TabInfo info = myTabs.findInfo(terminal);
      if (info != null) {
        myTabs.removeTab(info);
      }
    }

    private class TerminalTabLabel extends TabLabel {
      TerminalTabLabel(final JBTabsImpl tabs, final TabInfo info) {
        super(tabs, info);

        setOpaque(false);

        setFocusable(false);

        SimpleColoredComponent label = myLabel;

        //add more space between the label and the button
        label.setBorder(JBUI.Borders.emptyRight(5));

        label.addMouseListener(new MouseAdapter() {

          @Override
          public void mouseReleased(MouseEvent event) {
            handleMouse(event);
          }

          @Override
          public void mousePressed(MouseEvent event) {
            handleMouse(event);
          }

          private void handleMouse(MouseEvent e) {
            if (e.isPopupTrigger()) {
              JPopupMenu menu = createPopup();
              menu.show(e.getComponent(), e.getX(), e.getY());
            }
            else if (e.getButton() != MouseEvent.BUTTON2) {
              myTabs.select(getInfo(), true);

              if (e.getClickCount() == 2 && !e.isConsumed()) {
                e.consume();
                renameTab();
              }
            }
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getButton() == MouseEvent.BUTTON2) {
              if (myTabs.getSelectedInfo() == info) {
                closeCurrentSession();
              }
              else {
                myTabs.select(info, true);
              }
            }
          }
        });
      }

      protected JPopupMenu createPopup() {
        JPopupMenu popupMenu = new JPopupMenu();

        TerminalAction.addToMenu(popupMenu, JBTabbedTerminalWidget.this);

        JMenuItem rename = new JMenuItem("Rename Tab");

        rename.addActionListener(new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent actionEvent) {
            renameTab();
          }
        });

        popupMenu.add(rename);

        JMenuItem moveToEditor = new JMenuItem("Move to Editor");

        moveToEditor.addActionListener(new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            moveToEditor(myTabs.getTabAt(getSelectedIndex()));
          }
        });

        popupMenu.add(moveToEditor);

        return popupMenu;
      }

      private void renameTab() {
        new TabRenamer() {
          @Override
          protected JTextField createTextField() {
            JBTextField textField = new JBTextField() {
              private int myMinimalWidth;

              @Override
              public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                if (size.width > myMinimalWidth) {
                  myMinimalWidth = size.width;
                }

                return wider(size, myMinimalWidth);
              }

              private Dimension wider(Dimension size, int minimalWidth) {
                return new Dimension(minimalWidth + 10, size.height);
              }
            };
            if (myTabs.useSmallLabels()) {
              textField.setFont(com.intellij.util.ui.UIUtil.getFont(UIUtil.FontSize.SMALL, textField.getFont()));
            }
            textField.setOpaque(true);
            return textField;
          }
        }.install(getSelectedIndex(), getInfo().getText(), myLabel, new TabRenamer.RenameCallBack() {
          @Override
          public void setComponent(Component c) {
            myTabs.setTabDraggingEnabled(!(c instanceof JBTextField));

            setPlaceholderContent(true, (JComponent)c);
          }

          @Override
          public void setNewName(int index, String name) {
            setTitleAt(index, name);
          }
        });
      }
    }

    class MyDragOutDelegate implements TabInfo.DragOutDelegate {

      private TerminalSessionVirtualFileImpl myFile;
      private DragSession mySession;

      @Override
      public void dragOutStarted(@NotNull MouseEvent mouseEvent, @NotNull TabInfo info) {
        final TabInfo previousSelection = info.getPreviousSelection();
        final Image img = JBTabsImpl.getComponentImage(info);
        info.setHidden(true);
        if (previousSelection != null) {
          myTabs.select(previousSelection, true);
        }

        myFile = (TerminalSessionVirtualFileImpl)info.getObject();
        Presentation presentation = new Presentation(info.getText());
        presentation.setIcon(info.getIcon());
        mySession = getDockManager()
          .createDragSession(mouseEvent, new EditorTabbedContainer.DockableEditor(myProject, img, myFile, presentation,
                                                                                  info.getComponent().getPreferredSize(), false));
      }

      private DockManager getDockManager() {
        return DockManager.getInstance(myProject);
      }

      @Override
      public void processDragOut(@NotNull MouseEvent event, @NotNull TabInfo source) {
        mySession.process(event);
      }

      @Override
      public void dragOutFinished(@NotNull MouseEvent event, TabInfo source) {
        myFile.putUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN, Boolean.TRUE);


        myTabs.removeTab(source);

        mySession.process(event);

        myFile.putUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN, null);


        myFile = null;
        mySession = null;
      }

      @Override
      public void dragOutCancelled(TabInfo source) {
        source.setHidden(false);
        if (mySession != null) {
          mySession.cancel();
        }

        myFile = null;
        mySession = null;
      }
    }

    private void moveToEditor(TabInfo tabInfo) {
      TerminalSessionVirtualFileImpl file = (TerminalSessionVirtualFileImpl)tabInfo.getObject();
      file.putUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN, Boolean.TRUE);
      FileEditorManager.getInstance(myProject).openFile(file, true);
      myTabs.removeTab(tabInfo);
      file.putUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN, null);
    }
  }


}
